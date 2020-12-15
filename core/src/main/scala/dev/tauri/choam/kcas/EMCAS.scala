/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import java.util.{ ArrayList, Comparator }
import java.lang.invoke.VarHandle

import scala.annotation.tailrec
import java.util.concurrent.ThreadLocalRandom

// TODO: integrate with IBR

// Differences will be (as compared to IBR in the paper):
// - Descriptors might be `retire`d while still reachable from a ref.
//   - However, they will be EMCAS-finalized in this case.
// - For another difference, see the comment above ThreadContext#write/cas.

/**
 * Efficient Multi-word Compare and Swap:
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private[kcas] object EMCAS extends KCAS { self =>

  // Listing 1 in the paper:

  final object MCASDescriptor {
    // TODO: should always be inlined
    final val minArraySize = 8
  }

  final class MCASDescriptor(
    /**
     * Word descriptors
     *
     * Thread safety: we only read the list after reading the descriptor from a `Ref`;
     * we only mutate the list before writing the descriptor to a `Ref`.
     */
    val words: ArrayList[WordDescriptor[_]]
  ) extends EMCASDescriptorBase with self.Desc with self.Snap {

    override def withCAS[A](ref: Ref[A], ov: A, nv: A): Desc = {
      this.words.add(WordDescriptor[A](ref, ov, nv, this))
      this
    }

    override def snapshot(): Snap = {
      @tailrec
      def copy(
        from: ArrayList[WordDescriptor[_]],
        to: ArrayList[WordDescriptor[_]],
        newParent: MCASDescriptor,
        idx: Int,
        len: Int
      ): Unit = {
        if (idx < len) {
          to.add(from.get(idx).withParent(newParent))
          copy(from, to, newParent, idx + 1, len)
        }
      }
      val newArrCapacity = Math.max(this.words.size(), MCASDescriptor.minArraySize)
      val newArr = new ArrayList[WordDescriptor[_]](newArrCapacity)
      val r = new MCASDescriptor(newArr)
      copy(this.words, newArr, r, 0, this.words.size())
      r
    }

    def sort(): Unit = {
      this.words.sort(WordDescriptor.comparator)
    }

    override def tryPerform(): Boolean = {
      MCAS(this, helping = false)
    }

    override def cancel(): Unit =
      ()

    override def load(): Desc =
      this

    override def discard(): Unit =
      ()
  }

  final class WordDescriptor[A] private (
    val address: Ref[A],
    val ov: A,
    val nv: A,
    val parent: MCASDescriptor
  ) {

    private var _holder: EMCASWeakData[A] =
      _

    def holder: EMCASWeakData[A] =
      this._holder

    final def withParent(newParent: MCASDescriptor): WordDescriptor[A] =
      WordDescriptor[A](this.address, this.ov, this.nv, newParent)

    final override def toString: String =
      s"WordDescriptor(${this.address}, ${this.ov}, ${this.nv})"
  }

  final object WordDescriptor {

    def apply[A](address: Ref[A], ov: A, nv: A, parent: MCASDescriptor): WordDescriptor[A] = {
      val r = new WordDescriptor[A](address, ov, nv, parent)
      r._holder = new EMCASWeakData[A](r)
      VarHandle.releaseFence()
      r
    }

    val comparator: Comparator[WordDescriptor[_]] = new Comparator[WordDescriptor[_]] {
      final override def compare(x: WordDescriptor[_], y: WordDescriptor[_]): Int = {
        // NB: `x ne y` is always true, because we create fresh descriptors in `withCAS`
        val res = Ref.globalCompare(x.address, y.address)
        if (res == 0) {
          assert(x.address eq y.address)
          KCAS.impossibleKCAS(x.address, x.ov, x.nv, y.ov, y.nv)
        } else {
          res
        }
      }
    }
  }

  // Listing 2 in the paper:

  /**
   * A specialized version of `readInternal` from the paper
   *
   * Only returns the actual value (after possibly helping).
   * Cannot be called from an ongoing MCAS operation (but
   * can be called when we're only reading).
   *
   * (The other version of `readInternal`, specialized for
   * an ongoing MCAS operation is inlined into `tryWord` below,
   * see the do-while loop.)
   */
  @tailrec
  private[choam] final def readValue[A](ref: Ref[A], replace: Int = 256): A = {
    ref.unsafeTryRead() match {
      case w: EMCASWeakData[_] =>
        w.cast[A].get() match {
          case null =>
            val a = w.cast[A].getValueVolatile()
            if (equ(a, EMCASWeakData.UNINITIALIZED)) {
              throw new IllegalStateException
            }
            this.maybeReplaceDescriptor[A](ref, w.cast[A], a, replace = replace)
            a
          case wd =>
            val parentStatus = wd.parent.getStatus()
            if (parentStatus eq EMCASStatus.ACTIVE) {
              MCAS(wd.parent, helping = true) // help the other op
              readValue(ref, replace) // retry
            } else if (parentStatus eq EMCASStatus.SUCCESSFUL) {
              wd.nv
            } else { // FAILED
              wd.ov
            }
        }
      case a =>
        a
    }
  }

  private final def maybeReplaceDescriptor[A](ref: Ref[A], ov: EMCASWeakData[A], nv: A, replace: Int): Unit = {
    if (replace != 0) {
      val n = ThreadLocalRandom.current().nextInt()
      if ((n % replace) == 0) {
        ref.unsafeTryPerformCas(ov.asInstanceOf[A], nv)
        // If this CAS fails, someone else might've been
        // replaced the desc with the final value, or
        // maybe started another operation; in either case,
        // there is nothing to do here.
        ()
      }
    }
  }

  // Listing 3 in the paper:

  private[choam] override def tryReadOne[A](ref: Ref[A]): A =
    readValue(ref)

  /**
   * Performs an MCAS operation.
   *
   * @param desc: The main descriptor.
   * @param helping: Pass `true` when helping `desc` found in a `Ref`;
   *                 `false` when `desc` is a new descriptor.
   */
  def MCAS(desc: MCASDescriptor, helping: Boolean): Boolean = {
    @tailrec
    def tryWord[A](wordDesc: WordDescriptor[A]): Boolean = {
      var content: A = nullOf[A]
      var value: A = nullOf[A]
      var go = true
      // Read `content`, and `value` if necessary;
      // this is a specialized and inlined version
      // of `readInternal` from the paper. We're
      // using a do-while loop instead of a tail-recursive
      // function (like in the paper), because we may
      // need both `content` and `value`, and returning
      // them would require allocating a tuple (like in
      // the paper).
      do {
        content = wordDesc.address.unsafeTryRead()
        content match {
          case w: EMCASWeakData[_] =>
            w.cast[A].get() match {
              case null =>
                value = w.cast[A].getValueVolatile()
                if (equ(value, EMCASWeakData.UNINITIALIZED)) {
                  throw new IllegalStateException
                }
                go = false
              case wd =>
                if (wd eq wordDesc) {
                  // already points to the right place, early return:
                  return true // scalastyle:ignore return
                } else {
                  // At this point, we're sure that `wd` belongs to another op
                  // (not `desc`), because otherwise it would've been equal to
                  // `wordDesc` (we're assuming that any WordDescriptor only
                  // appears at most once in an MCASDescriptor).
                  val parentStatus = wd.parent.getStatus()
                  if (parentStatus eq EMCASStatus.ACTIVE) {
                    MCAS(wd.parent, helping = true) // help the other op
                    // Note: we're not "helping" ourselves for sure, see the comment above.
                    // Here, we still don't have the value, so the loop must retry.
                  } else if (parentStatus eq EMCASStatus.SUCCESSFUL) {
                    value = wd.nv
                    go = false
                  } else {
                    value = wd.ov
                    go = false
                  }
                }
            }
          case a =>
            value = a
            go = false
        }
      } while (go)

      if (!equ(value, wordDesc.ov)) {
        // expected value is different
        false
      } else if (desc.getStatus() ne EMCASStatus.ACTIVE) {
        // we have been finalized (by a helping thread), no reason to continue
        true // TODO: we should break from `go`
        // TODO: `true` is not necessarily correct, the helping thread could've finalized us to failed too
      } else {
        val weakData = wordDesc.holder
        if (!wordDesc.address.unsafeTryPerformCas(content, weakData.asInstanceOf[A])) {
          tryWord(wordDesc) // retry this word
        } else {
          true
        }
      }
    }

    @tailrec
    def go(words: java.util.Iterator[WordDescriptor[_]]): Boolean = {
      if (words.hasNext) {
        if (tryWord(words.next())) go(words)
        else false
      } else {
        true
      }
    }

    @tailrec
    def writeFinalValues[A](
      success: Boolean,
      curr: WordDescriptor[A],
      words: java.util.Iterator[WordDescriptor[_]]
    ): Unit = {
      val finalValue = if (success) curr.nv else curr.ov
      if (words.hasNext) {
        curr.holder.setValuePlain(finalValue)
        writeFinalValues(success, words.next(), words)
      } else {
        curr.holder.setValueVolatile(finalValue) // FIXME: release?
      }
    }

    if (!helping) {
      // we're not helping, so `desc` is not yet visible to other threads
      desc.sort()
    } // else: the thread which published `desc` already sorted it
    val success = go(desc.words.iterator())
    if (desc.casStatus(
      EMCASStatus.ACTIVE,
      if (success) EMCASStatus.SUCCESSFUL else EMCASStatus.FAILED
    )) {
      // we finalized the descriptor, so we record final values:
      val it = desc.words.iterator()
      if (it.hasNext) {
        writeFinalValues(success, it.next(), it)
      }
      // make sure GC doesn't clear weakrefs before we could set the final values:
      java.lang.ref.Reference.reachabilityFence(desc)
      success
    } else {
      // someone else finalized the descriptor, we must read its status:
      desc.getStatus() eq EMCASStatus.SUCCESSFUL
    }
  }

  private[choam] override def start(): Desc = {
    new MCASDescriptor(new ArrayList(MCASDescriptor.minArraySize))
  }

  /** For testing */
  @throws[InterruptedException]
  private[kcas] def spinUntilCleanup[A](ref: Ref[A]): A = {
    var desc: WordDescriptor[_] = null
    var ctr: Int = 0
    while (true) {
      ref.unsafeTryRead() match {
        case wd: EMCASWeakData[_] =>
          desc = wd.get()
          if (desc ne null) {
            if (desc.parent.getStatus() eq EMCASStatus.ACTIVE) {
              // CAS in progress, retry
              desc = null
            } else {
              // CAS finalized, but no cleanup yet, retry
              desc = null
              System.gc()
            }
          } else {
            // descriptor have been collected, but not replaced yet;
            // this should replace it with a small probability (if
            // not, we'll retry):
            EMCAS.tryReadOne(ref)
          }
        case a =>
          // descriptor have been cleaned up:
          return a // scalastyle:ignore return
      }
      Thread.onSpinWait()
      ctr += 1
      if ((ctr % 1024) == 0) {
        if (Thread.interrupted()) {
          throw new InterruptedException
        }
      }
    }
    // unreachable code:
    return nullOf[A] // scalastyle:ignore return
  }
}
