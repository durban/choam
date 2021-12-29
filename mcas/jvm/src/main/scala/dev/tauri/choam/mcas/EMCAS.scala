/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import java.lang.ref.Reference

/**
 * Efficient Multi-word Compare and Swap:
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private object EMCAS extends MCAS { self =>

  // These values for the replace period and limit
  // have been determined experimentally. See for example
  // RandomReplaceBench, StackTransferBench and GcBench.

  // InterpreterBench:
  // replace-> throughput
  // 131072 -> ≈ 32000 ops/s
  // 65536  -> 47054.936 ± 1533.734  ops/s
  // 32768  -> 46914.121 ± 1109.186  ops/s
  // 16384  -> 46358.343 ± 541.677  ops/s
  // 8192   -> 48157.547 ± 1158.838  ops/s
  // 4096   -> 49038.932 ± 1003.262  ops/s
  // 2048   -> 48625.019 ± 1551.751  ops/s

  private[choam] final val replacePeriodForEMCAS =
    4096

  private[choam] final val replacePeriodForReadValue =
    4096

  private[choam] final val limitForFinalizedList =
    1024

  private[choam] val global =
    new GlobalContext(self)

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
   * see the `while` loop.)
   *
   * @param ref: The [[MemoryLocation]] to read from.
   * @param ctx: The [[ThreadContext]] of the current thread.
   * @param replace: The period with which to run GC (IBR) when encountering
   *                 a descriptor. Should be a power of 2; higher values
   *                 make the GC run less frequently.
   */
  private[choam] final def readValue[A](ref: MemoryLocation[A], ctx: ThreadContext, replace: Int): A = {
    @tailrec
    def go(): A = {
      ref.unsafeGetVolatile() match {
        case wd: WordDescriptor[_] =>
          val mark = wd.tryHold()
          val parentStatus = wd.parent.getStatus()
          if (parentStatus eq EMCASStatus.ACTIVE) {
            assert(mark ne null)
            MCAS(wd.parent, ctx = ctx, replace = replace) // help the other op
            Reference.reachabilityFence(mark)
            go() // retry
          } else { // finalized
            val a = if (parentStatus eq EMCASStatus.SUCCESSFUL) {
              wd.cast[A].nv
            } else { // FAILED
              wd.cast[A].ov
            }
            this.maybeReplaceDescriptor[A](ref, wd.cast[A], a, ctx, replace = replace)
            a
          }
        case a =>
          a
      }
    }

    go()
  }

  private final def maybeReplaceDescriptor[A](ref: MemoryLocation[A], ov: WordDescriptor[A], nv: A, ctx: ThreadContext, replace: Int): Unit = {
    if (replace != 0) {
      val n = ctx.random.nextInt()
      if ((n % replace) == 0) {
        replaceDescriptorIfFree[A](ref, ov, nv)
        ()
      }
    }
  }

  private[mcas] final def replaceDescriptorIfFree[A](
    ref: MemoryLocation[A],
    ov: WordDescriptor[A],
    nv: A,
  ): Boolean = {
    if (ov.isInUse()) {
      // still in use, need to be replaced later
      false
    } else {
      ref.unsafeCasVolatile(ov.castToData, nv)
      // If this CAS fails, someone else might've
      // replaced the desc with the final value, or
      // maybe started another operation; in either case,
      // there is nothing to do, so we indicate success.
      true
    }
  }

  // Listing 3 in the paper:

  private[mcas] final def read[A](ref: MemoryLocation[A], ctx: ThreadContext): A =
    readValue(ref, ctx, EMCAS.replacePeriodForReadValue)

  /**
   * Performs an MCAS operation.
   *
   * @param desc: The main descriptor.
   * @param ctx: The [[ThreadContext]] of the current thread.
   * @param replace: The period with which to run GC (IBR) after finalizing
   *                 an operation. Should be a power of 2; higher values
   *                 make the GC run less frequently.
   */
  def MCAS(desc: EMCASDescriptor, ctx: ThreadContext, replace: Int): Boolean = {

    @tailrec
    def tryWord[A](wordDesc: WordDescriptor[A]): TryWordResult = {
      var content: A = nullOf[A]
      var contentWd: WordDescriptor[A] = null
      var value: A = nullOf[A]
      var go = true
      // Read `content`, and `value` if necessary;
      // this is a specialized and inlined version
      // of `readInternal` from the paper. We're
      // using a `while` loop instead of a tail-recursive
      // function (like in the paper), because we may
      // need both `content` and `value`, and returning
      // them would require allocating a tuple (like in
      // the paper).
      while (go) {
        contentWd = null
        content = wordDesc.address.unsafeGetVolatile()
        content match {
          case wd: WordDescriptor[_] =>
            val mark = wd.tryHold()
            if (wd eq wordDesc) {
              // already points to the right place, early return:
              return TryWordResult.SUCCESS // scalafix:ok
            } else {
              contentWd = wd.cast[A]
              // At this point, we're sure that `wd` belongs to another op
              // (not `desc`), because otherwise it would've been equal to
              // `wordDesc` (we're assuming that any WordDescriptor only
              // appears at most once in an MCASDescriptor).
              val parentStatus = wd.parent.getStatus()
              if (parentStatus eq EMCASStatus.ACTIVE) {
                MCAS(wd.parent, ctx = ctx, replace = replace) // help the other op
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
            Reference.reachabilityFence(mark)
          case a =>
            value = a
            go = false
        }
      }

      if (!equ(value, wordDesc.ov)) {
        // expected value is different
        TryWordResult.FAILURE
      } else if (desc.getStatus() ne EMCASStatus.ACTIVE) {
        // we have been finalized (by a helping thread), no reason to continue
        TryWordResult.BREAK
      } else {
        val weakRefOk = extendInterval(oldWd = contentWd, newWd = wordDesc)
        if (weakRefOk && wordDesc.address.unsafeCasVolatile(content, wordDesc.castToData)) {
          TryWordResult.SUCCESS
        } else {
          // either we couldn't chain the descriptors, or
          // the CAS on the `Ref` failed; in either case,
          // we'll retry:
          tryWord(wordDesc)
        }
      }
    }

    // TODO: better name (there are no "intervals" any more)
    def extendInterval[A](oldWd: WordDescriptor[A], newWd: WordDescriptor[A]): Boolean = {
      if (oldWd ne null) {
        val mark = oldWd.tryHold()
        if (mark ne null) {
          val res = newWd.casPredecessor(null, oldWd)
          res
        } else {
          true // already can be replaced without issue, nothing to do
        }
      } else {
        true // there was no old descriptor
      }
    }

    @tailrec
    def go(words: java.util.Iterator[WordDescriptor[_]]): TryWordResult = {
      if (words.hasNext) {
        val word = words.next()
        if (word ne null) {
          val mark = word.tryHold()
          if (mark ne null) {
            val twr = tryWord(word)
            Reference.reachabilityFence(mark)
            if (twr eq TryWordResult.SUCCESS) go(words)
            else twr
          } else {
            assert(desc.getStatus() ne EMCASStatus.ACTIVE)
            TryWordResult.BREAK
          }
        } else {
          // Another thread already finalized the descriptor,
          // and cleaned up this word descriptor (hence the `null`);
          // thus, we should not continue:
          assert(desc.getStatus() ne EMCASStatus.ACTIVE)
          TryWordResult.BREAK
        }
      } else {
        TryWordResult.SUCCESS
      }
    }

    val r = go(desc.wordIterator())
    if (r eq TryWordResult.BREAK) {
      // someone else finalized the descriptor, we must read its status:
      (desc.getStatus() eq EMCASStatus.SUCCESSFUL)
    } else {
      val rr = if (r eq TryWordResult.SUCCESS) {
        EMCASStatus.SUCCESSFUL
      } else {
        EMCASStatus.FAILED
      }
      if (desc.casStatus(EMCASStatus.ACTIVE, rr)) {
        // TODO: If we crash exactly here, no thread will clear the markers;
        // TODO: this is not a correctness issue, but a performance one.
        desc.wordIterator().forEachRemaining(_.finalizeWd())
        // TODO: clearing the strong ref is not enough,
        // TODO: occasionally we should run over finalized
        // TODO: descriptors, look at their weakrefs, and
        // TODO: if cleared, we should replace them (with the final value).
        (rr eq EMCASStatus.SUCCESSFUL)
      } else {
        // someone else finalized the descriptor, we must read its status:
        (desc.getStatus() eq EMCASStatus.SUCCESSFUL)
      }
    }
  }

  final override def currentContext(): ThreadContext =
    this.global.threadContext()

  private[choam] final override def isThreadSafe =
    true

  private[mcas] final def tryPerform(desc: HalfEMCASDescriptor, ctx: ThreadContext): Boolean = {
    tryPerformDebug(desc = desc, ctx = ctx, replace = EMCAS.replacePeriodForEMCAS)
  }

  private[mcas] final def tryPerformDebug(desc: HalfEMCASDescriptor, ctx: ThreadContext, replace: Int): Boolean = {
    if (desc.nonEmpty) {
      val fullDesc = EMCASDescriptor.prepare(desc)
      EMCAS.MCAS(desc = fullDesc, ctx = ctx, replace = replace)
    } else {
      true
    }
  }

  private[choam] final override def printStatistics(println: String => Unit): Unit = {
    ()
  }

  private[choam] final override def countCommitsAndRetries(): (Long, Long) = {
    this.global.countCommitsAndRetries()
  }

  /** For testing */
  @throws[InterruptedException]
  private[mcas] def spinUntilCleanup[A](ref: MemoryLocation[A], max: Long = Long.MaxValue): A = {
    val ctx = this.currentContext()
    var ctr: Long = 0L
    while (ctr < max) {
      ref.unsafeGetVolatile() match {
        case wd: WordDescriptor[_] =>
          if (wd.parent.getStatus() eq EMCASStatus.ACTIVE) {
            // CAS in progress, retry
          } else {
            // CAS finalized, but no cleanup yet, read and retry
            EMCAS.read(ref, ctx)
          }
        case a =>
          // descriptor have been cleaned up:
          return a // scalafix:ok
      }
      Thread.onSpinWait()
      ctr += 1L
      if ((ctr % 128L) == 0L) {
        if (Thread.interrupted()) {
          throw new InterruptedException
        } else {
          System.gc()
        }
      }
    }
    nullOf[A]
  }
}
