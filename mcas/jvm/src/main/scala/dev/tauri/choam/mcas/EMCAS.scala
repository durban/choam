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

import java.lang.ref.{ Reference, WeakReference }

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

  // TODO: this is unused
  private[choam] final val replacePeriodForReadValue =
    4096

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
   * @param replace: Pass 0 to not do any replacing/clearing.
   */
  private[choam] final def readValue[A](ref: MemoryLocation[A], ctx: ThreadContext, replace: Int): A = {
    @tailrec
    def go(mark: AnyRef): A = {
      ref.unsafeGetVolatile() match {
        case wd: WordDescriptor[_] =>
          if (mark eq null) {
            // not holding it yet
            val weakref = ref.unsafeGetMarkerVolatile()
            val m = if (weakref ne null) weakref.get() else null
            if (m ne null) {
              // we're holding it, re-read the descriptor:
              go(mark = m)
            } else { // m eq null (from either a cleared or a removed weakref)
              // descriptor can be detached
              val parentStatus = wd.parent.getStatus()
              if (parentStatus eq EMCASStatus.ACTIVE) {
                // active op without a mark: this can
                // happen if a thread died during an op;
                // we help the active op, then retry ours:
                MCAS(wd.parent, ctx = ctx)
                go(mark = null)
              } else { // finalized op
                val a = if (parentStatus eq EMCASStatus.SUCCESSFUL) {
                  wd.cast[A].nv
                } else { // FAILED
                  wd.cast[A].ov
                }
                // marker is null, so we can replace the descriptor:
                this.maybeReplaceDescriptor[A](
                  ref,
                  wd.cast[A],
                  a,
                  weakref = weakref,
                  replace = replace,
                )
                a
              }
            }
          } else { // mark ne null
            // OK, we're already holding the descriptor
            val parentStatus = wd.parent.getStatus()
            if (parentStatus eq EMCASStatus.ACTIVE) {
              MCAS(wd.parent, ctx = ctx) // help the other op
              go(mark = mark) // retry
            } else { // finalized
              val a = if (parentStatus eq EMCASStatus.SUCCESSFUL) {
                wd.cast[A].nv
              } else { // FAILED
                wd.cast[A].ov
              }
              a
            }
          }
        case a =>
          a
      }
    }

    go(mark = null)
  }

  private[this] final def maybeReplaceDescriptor[A](
    ref: MemoryLocation[A],
    ov: WordDescriptor[A],
    nv: A,
    weakref: WeakReference[AnyRef],
    replace: Int,
  ): Unit = {
    if (replace != 0) {
      replaceDescriptor[A](ref, ov, nv, weakref)
    }
  }

  private[this] final def replaceDescriptor[A](
    ref: MemoryLocation[A],
    ov: WordDescriptor[A],
    nv: A,
    weakref: WeakReference[AnyRef],
  ): Unit = {
    // We replace the descriptor with the final value.
    // If this CAS fails, someone else might've
    // replaced the desc with the final value, or
    // maybe started another operation; in either case,
    // there is nothing to do here.
    ref.unsafeCasVolatile(ov.castToData, nv)
    if (weakref ne null) {
      assert(weakref.get() eq null)
      // We also delete the (now empty) `WeakReference`
      // object, to help the GC. If this CAS fails,
      // that means a new op already installed a new
      // weakref; nothing to do here.
      ref.unsafeCasMarkerVolatile(weakref, null)
      ()
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
   * @param replace:
   */
  def MCAS(desc: EMCASDescriptor, ctx: ThreadContext): Boolean = {

    @tailrec
    def tryWord[A](wordDesc: WordDescriptor[A]): TryWordResult = {
      var content: A = nullOf[A]
      var contentWd: WordDescriptor[A] = null
      var value: A = nullOf[A]
      var weakref: WeakReference[AnyRef] = null
      var mark: AnyRef = null
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
            if (mark eq null) {
              // not holding it yet
              weakref = wordDesc.address.unsafeGetMarkerVolatile()
              mark = if (weakref ne null) weakref.get() else null
              if (mark ne null) {
                // continue with another iteration, and re-read the
                // descriptor, while holding the mark
              } else { // mark eq null
                // the old descriptor is unused, could be detached
                val parentStatus = wd.parent.getStatus()
                if (parentStatus eq EMCASStatus.ACTIVE) {
                  // active op without a mark: this can
                  // happen if a thread died during an op
                  if (wd eq wordDesc) {
                    // this is us!
                    // already points to the right place, early return:
                    return TryWordResult.SUCCESS // scalafix:ok
                  } else {
                    // we help the active op (who is not us),
                    // then continue with another iteration:
                    MCAS(wd.parent, ctx = ctx)
                  }
                } else { // finalized op
                  if (parentStatus eq EMCASStatus.SUCCESSFUL) {
                    value = wd.cast[A].nv
                    go = false
                  } else { // FAILED
                    value = wd.cast[A].ov
                    go = false
                  }
                }
              }
            } else { // mark ne null
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
                  MCAS(wd.parent, ctx = ctx) // help the other op
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
            }
          case a =>
            value = a
            go = false
            weakref = wordDesc.address.unsafeGetMarkerVolatile()
            // we found a value (i.e., not a descriptor)
            if (weakref ne null) {
              // in rare cases, `mark` could be non-null here
              // (see below); but that is not a problem, we
              // hold it here, and will use it for our descriptor
              mark = weakref.get()
            } else {
              // we need to clear a possible non-null mark from
              // a previous iteration when we found a descriptor:
              mark = null
            }
        }
      }

      Reference.reachabilityFence(mark) // TODO: this is probably unnecessary

      if (!equ(value, wordDesc.ov)) {
        // expected value is different
        TryWordResult.FAILURE
      } else if (desc.getStatus() ne EMCASStatus.ACTIVE) {
        // we have been finalized (by a helping thread), no reason to continue
        TryWordResult.BREAK
      } else {
        // before installing our descriptor, make sure a valid mark exists:
        val weakRefOk = if (mark eq null) {
          assert((weakref eq null) || (weakref.get() eq null))
          // there was no old descriptor, or it was already unused;
          // we'll need a new mark:
          mark = ctx.getReusableMarker()
          val weakref2 = ctx.getReusableWeakRef()
          assert(weakref2.get() eq mark)
          wordDesc.address.unsafeCasMarkerVolatile(weakref, weakref2)
          // if this fails, we'll retry, see below
        } else {
          // we have a valid mark from reading
          true
        }
        // If *right now* (after the CAS), another thread, which started
        // reading before we installed a new weakref above, finishes its
        // read, and detaches the *previous* descriptor (since we
        // haven't installed ours yet, and that one was unused);
        // then the following CAS will fail (not a problem), and
        // on our next retry, we may see a ref with a value *and*
        // a non-empty weakref (but this case is also handled, see above).
        if (weakRefOk && wordDesc.address.unsafeCasVolatile(content, wordDesc.castToData)) {
          Reference.reachabilityFence(mark)
          TryWordResult.SUCCESS
        } else {
          // either we couldn't install the new mark, or
          // the CAS on the `Ref` failed; in either case,
          // we'll retry:
          Reference.reachabilityFence(mark) // TODO: do we need this? Probably yes.
          tryWord(wordDesc)
        }
      }
    } // tryWord

    @tailrec
    def go(words: java.util.Iterator[WordDescriptor[_]]): TryWordResult = {
      if (words.hasNext) {
        val word = words.next()
        if (word ne null) {
          val twr = tryWord(word)
          if (twr eq TryWordResult.SUCCESS) go(words)
          else twr
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
        (rr eq EMCASStatus.SUCCESSFUL)
      } else {
        // someone else finalized the descriptor, we must read its status:
        (desc.getStatus() eq EMCASStatus.SUCCESSFUL)
        // TODO: instead of this, we should cmpxchg the status (and not `casStatus`)
      }
    }
  }

  final override def currentContext(): ThreadContext =
    this.global.threadContext()

  private[choam] final override def isThreadSafe =
    true

  private[mcas] final def tryPerform(desc: HalfEMCASDescriptor, ctx: ThreadContext): Boolean = {
    tryPerformDebug(desc = desc, ctx = ctx)
  }

  private[mcas] final def tryPerformDebug(desc: HalfEMCASDescriptor, ctx: ThreadContext): Boolean = {
    if (desc.nonEmpty) {
      val fullDesc = EMCASDescriptor.prepare(desc)
      EMCAS.MCAS(desc = fullDesc, ctx = ctx)
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

  private[choam] final override def collectExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    this.global.collectExchangerStats()
  }

  /** Only for testing/benchmarking */
  private[choam] final override def maxReusedWeakRefs(): Int = {
    this.global.maxReusedWeakRefs()
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
