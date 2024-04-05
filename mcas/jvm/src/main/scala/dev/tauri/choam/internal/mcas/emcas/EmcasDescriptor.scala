/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.tauri.choam
package internal
package mcas
package emcas

import java.util.concurrent.ThreadLocalRandom

private[mcas] final class EmcasDescriptor private[this] (
  half: Descriptor,
  wordsToCopy: Array[EmcasWordDesc[_]],
) extends EmcasDescriptorBase { self =>

  /**
   * We want an identity `hashCode`, but it's probably
   * better to avoid `System.identityHashCode`, because
   * it typically does a CAS (the first time it's called
   * on an object).
   */
  final override val hashCode: Int =
    ThreadLocalRandom.current().nextInt()

  private[emcas] final val instRo: Boolean =
    (wordsToCopy ne null)

  private[emcas] def this(half: Descriptor) =
    this(half, null)

  private def this(wordsToCopy: Array[EmcasWordDesc[_]]) =
    this(null, wordsToCopy)

  // EMCAS handles the global version
  // separately, so the descriptor must
  // not have a CAS for changing it:
  assert((half eq null) || (!half.hasVersionCas))

  /*
   * While the status is `Active`, this array
   * is never mutated. After the op is finalized,
   * it may be cleared or set to null (to help GC),
   * so helpers must be prepared to handle `null`s.
   * (There  is no need to help an op which is
   * finalized, so this is not a problem.)
   *
   * The plain/opaque writes in the constructor
   * are made visible by the volatile-CASes which insert
   * the `EmcasWordDesc`s into the refs.
   */
  this.setWordsO(if (half ne null) {
    assert(wordsToCopy eq null)
    val arr = half.toWdArray(this).asInstanceOf[Array[EmcasWordDesc[_]]]
    require(arr.length > 0)
    arr
  } else {
    // we're a fallback
    assert(wordsToCopy ne null)
    val len = wordsToCopy.length
    require(len > 0)
    val arr = new Array[EmcasWordDesc[_]](len)
    var idx = 0
    while (idx < len) {
      wordsToCopy(idx) match {
        case null =>
          // the array is being cleared, we can't continue here;
          // instead of throwing an exception, we do the ugly
          // thing, and store a sentinel into the first array slot:
          arr(0) = EmcasWordDesc.Invalid
          idx = len // break while
        case wd =>
          arr(idx) = wd.withParent(this)
          idx += 1
      }
    }
    arr
  })

  /** May return `null` for finalized descriptors */
  private[emcas] final def getWordDescArrOrNull(): Array[EmcasWordDesc[_]] = {
    // This is a racy read, but if we get
    // null, the decriptor is finalized, so
    // that's fine, we don't need to continue anyway.
    // If we get non-null, we'll see the array
    // elements (unless later cleared), because
    // they were written originally in the
    // constructor, and we obtained the
    // `EmcasDescriptor` from a
    // `EmcasWordDesc` which we obtained
    // with a volatile-read from a ref (or
    // we created it originally).
    this.getWordsO()
  }

  /** Only for testing! */
  private[emcas] final def getWordIterator(): java.util.Iterator[EmcasWordDesc[_]] = {
    this.getWordsO() match {
      case null => null
      case words => new EmcasDescriptor.Iterator(words)
    }
  }

  private[emcas] final def wasFinalized(finalResult: Long): Unit = {
    if (finalResult == EmcasStatus.CycleDetected) {
      assert(!this.instRo)
      // create the fallback, we'll need it
      // anyway, no reason to wait for lazy-init:
      val fb = new EmcasDescriptor(this.getWordsO())
      assert(fb.getWordsP()(0) ne EmcasWordDesc.Invalid)
      // but we have to store it carefully,
      // someone else might've beat us:
      this.getOrInitFallback(fb)
    }

    // we can clean up to help the GC
    // (even if we have to retry with
    // the fallback, we already copied
    // the array):
    this.cleanWordsForGc()
  }

  private[emcas] final def fallback: EmcasDescriptor = {
    val fb = this.getFallbackA()
    if (fb eq null) {
      assert(this.getStatus() == EmcasStatus.CycleDetected)
      assert(!this.instRo)
      this.getWordsO() match {
        case null =>
          // `wasFinalized` cleared the array, so it already
          // stored the correct fallback; instead of spinning
          // on `getFallbackA` (it's unclear if that counts
          // as lock-free; in theory eventually we'll see the
          // value) we have to do a surely failing CAS to
          // immediately get the value; however, it's very
          // likely that `getFallbackA` is enough (and it's
          // probably much cheaper than a CAS), so we try it
          // ONCE, then (if not enough) we do the CAS:
          this.getFallbackFromHelper()
        case wds =>
          val candidate = new EmcasDescriptor(wds)
          if (candidate.getWordsP()(0) eq EmcasWordDesc.Invalid) {
            // `wasFinalized`, see above
            this.getFallbackFromHelper()
          } else {
            this.getOrInitFallback(candidate)
          }
      }
    } else {
      fb
    }
  }

  private[this] final def getFallbackFromHelper(): EmcasDescriptor = {
    this.getFallbackA() match {
      case null =>
        // wasn't enough:
        val fb2 = this.cmpxchgFallbackA(null, null)
        assert(fb2 ne null)
        fb2
      case fb2 =>
        // found it:
        fb2
    }
  }

  final override def toString: String = {
    this.getWordsO() match {
      case null => "EMCASDescriptor(-)"
      case words => s"EMCASDescriptor(size = ${words.length})"
    }
  }
}

private object EmcasDescriptor {

  def prepare(half: Descriptor): EmcasDescriptor = {
    new EmcasDescriptor(half)
  }

  private final class Iterator(words: Array[EmcasWordDesc[_]])
    extends java.util.Iterator[EmcasWordDesc[_]] {

    private[this] var idx: Int =
      0

    final override def hasNext(): Boolean = {
      this.idx < this.words.length
    }

    final override def next(): EmcasWordDesc[_] = {
      val idx = this.idx
      val words = this.words
      if (idx < words.length) {
        this.idx = idx + 1
        this.words(idx)
      } else {
        throw new NoSuchElementException
      }
    }

    final override def remove(): Unit = {
      throw new UnsupportedOperationException("EmcasDescriptor.Iterator#remove")
    }
  }
}
