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

private[mcas] final class EmcasDescriptor private[emcas] (
  half: Descriptor,
) extends EmcasDescriptorBase { self =>

  // EMCAS handles the global version
  // separately, so the descriptor must
  // not have a CAS for changing it:
  assert(!half.hasVersionCas)

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
   * the `WordDescriptor`s into the refs.
   */
  this.setWordsO({
    val arr = new Array[WordDescriptor[_]](half.size)
    val it = half.iterator()
    var idx = 0
    while (it.hasNext) {
      val wd = new WordDescriptor(it.next(), this)
      arr(idx) = wd
      idx += 1
    }
    arr
  })

  /** Can return `null` for finalized descriptors */
  private[emcas] final def wordIterator(): java.util.Iterator[WordDescriptor[_]] = {
    // This is a racy read, but if we get
    // null, the decriptor is finalized, so
    // that's fine, we don't need to help anyway.
    // If we get non-null, we'll see the array
    // elements (unless later cleared), because
    // they were written originally in the
    // constructor, and we obtained the
    // `EmcasDescriptor` from a
    // `WordDescriptor` which we obtained
    // with a volatile-read from a ref.
    this.getWordsO() match {
      case null => null
      case words => new EmcasDescriptor.Iterator(words) // TODO: try to use a no-alloc cursor
    }
  }

  private[emcas] final def wasFinalized(): Unit = {
    // help the GC:
    this.cleanWordsForGc()
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

  private final class Iterator(words: Array[WordDescriptor[_]])
    extends java.util.Iterator[WordDescriptor[_]] {

    private[this] var idx: Int =
      0

    final override def hasNext(): Boolean = {
      this.idx < this.words.length
    }

    final override def next(): WordDescriptor[_] = {
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
