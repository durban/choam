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

import java.util.concurrent.ThreadLocalRandom

import scala.annotation.tailrec

/**
 * Efficient Multi-word Compare and Swap:
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private[kcas] object EMCAS extends KCAS { self =>

  private[this] val global =
    new GlobalContext

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
      case wd: WordDescriptor[_] =>
        val parentStatus = wd.parent.getStatus()
        if (parentStatus eq EMCASStatus.ACTIVE) {
          MCAS(wd.parent, helping = true) // help the other op
          readValue(ref, replace) // retry
        } else { // finalized
          val a = if (parentStatus eq EMCASStatus.SUCCESSFUL) {
            wd.cast[A].nv
          } else { // FAILED
            wd.cast[A].ov
          }
          this.maybeReplaceDescriptor[A](ref, wd.cast[A], a, replace = replace)
          a
        }
      case a =>
        a
    }
  }

  private final def maybeReplaceDescriptor[A](ref: Ref[A], ov: WordDescriptor[A], nv: A, replace: Int): Unit = {
    if (replace != 0) {
      val n = ThreadLocalRandom.current().nextInt()
      if ((n % replace) == 0) { // TODO: also check if `ov` can be replaced!
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

  private[choam] final override def read[A](ref: Ref[A], ctx: ThreadContext): A =
    readValue(ref)

  /**
   * Performs an MCAS operation.
   *
   * @param desc: The main descriptor.
   * @param helping: Pass `true` when helping `desc` found in a `Ref`;
   *                 `false` when `desc` is a new descriptor.
   */
  def MCAS(desc: EMCASDescriptor, helping: Boolean): Boolean = {
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
          case wd: WordDescriptor[_] =>
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
                value = wd.cast[A].nv
                go = false
              } else {
                value = wd.cast[A].ov
                go = false
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
        if (!wordDesc.address.unsafeTryPerformCas(content, wordDesc.asInstanceOf[A])) {
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

    if (!helping) {
      // we're not helping, so `desc` is not yet visible to other threads
      desc.sort()
    } // else: the thread which published `desc` already sorted it
    val success = go(desc.words.iterator())
    if (desc.casStatus(
      EMCASStatus.ACTIVE,
      if (success) EMCASStatus.SUCCESSFUL else EMCASStatus.FAILED
    )) {
      success
    } else {
      // someone else finalized the descriptor, we must read its status:
      desc.getStatus() eq EMCASStatus.SUCCESSFUL
    }
  }

  private[choam] final override def currentContext(): ThreadContext =
    this.global.currentContext()

  private[choam] final override def start(ctx: ThreadContext): EMCASDescriptor = {
    new EMCASDescriptor(this)
  }

  private[choam] final override def addCas[A](
    desc: EMCASDescriptor,
    ref: Ref[A],
    ov: A,
    nv: A
  ): EMCASDescriptor = {
    desc.words.add(WordDescriptor[A](ref, ov, nv, desc))
    desc
  }

  private[choam] final override def snapshot(desc: EMCASDescriptor): EMCASDescriptor =
    desc.copy()

  private[choam] final override def tryPerform(desc: EMCASDescriptor, ctx: ThreadContext): Boolean = {
    EMCAS.MCAS(desc, helping = false)
  }

  /** For testing */
  @throws[InterruptedException]
  private[kcas] def spinUntilCleanup[A](ref: Ref[A]): A = {
    val ctx = this.currentContext()
    var ctr: Int = 0
    while (true) {
      ref.unsafeTryRead() match {
        case wd: WordDescriptor[_] =>
          if (wd.parent.getStatus() eq EMCASStatus.ACTIVE) {
            // CAS in progress, retry
          } else {
            // CAS finalized, but no cleanup yet, read and retry
            EMCAS.read(ref, ctx)
          }
        case a =>
          // descriptor have been cleaned up:
          return a // scalastyle:ignore return
      }
      Thread.onSpinWait()
      ctr += 1
      if ((ctr % 128) == 0) {
        if (Thread.interrupted()) {
          throw new InterruptedException
        }
      }
    }
    // unreachable code:
    return nullOf[A] // scalastyle:ignore return
  }
}
