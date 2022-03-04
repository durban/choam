/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
 * Efficient Multi-word Compare and Swap (EMCAS):
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private object Emcas extends MCAS { self => // TODO: make this a class

  /*
   * This implementation has a few important
   * differences from the one described in the paper.
   *
   *
   * ### Markers ###
   *
   * The paper which describes EMCAS omits detailed
   * description of the memory management technique.
   * However, as mentioned in the paper, memory management
   * is important not just for the performance, but for the
   * correctness of the algorithm.
   *
   * A finished EMCAS operation leaves `WordDescriptor`s
   * in the `MemoryLocation`s it touches. These can only
   * be removed (i.e., detached, replaced by the final value
   * or a new descriptor) if no other thread (helpers, or the
   * original thread, in case a helper completes the op) uses
   * the `WordDescriptor`. If they are removed while still
   * in use, that can cause ABA-problems. (Also, if existing
   * descriptor objects would be reused, it would cause other
   * problems too. However, currently only fresh descriptors
   * are used.)
   *
   * To guarantee that in-use descriptors are never replaced,
   * every thread (the original and any helpers) must always
   * hold a strong reference to the "mark(er)" associated with
   * the descriptor. This way, if the marker is collected by
   * the JVM GC, we can be sure, that the corresponding descriptor
   * can be replaced.
   *
   * Markers are manipulated with the `unsafeGetMarkerVolatile` and
   * `unsafeCasMarkerVolatile` methods of `MemoryLocation`. The
   * marker of a `MemoryLocation` can be in the following states:
   *
   *   null - `unsafeGetMarkerVolatile` returns null, i.e.,
   *   not even a `WeakReference` object exists.
   *
   *   empty - `unsafeGetMarkerVolatile` returns an empty,
   *   i.e., cleared `WeakReference` object.
   *
   *   full - `unsafeGetMarkerVolatile` returns a full, i.e.,
   *   not cleared `WeakReference` object.
   *
   * The other methods (e.g., `unsafeGetVolatile`) manipulate the "content"
   * of the `MemoryLocation`. If the content is a "value", that can be
   * freely replaced by a descriptor during an operation. (But the
   * new descriptor must have a mark.) The content can have the following
   * possible states:
   *
   *   value - a user-supplied value (possibly including `null`);
   *   this state includes anything that is not a `WordDescriptor`.
   *
   *   descriptor - a (non-null) `WordDescriptor` object.
   *
   * Thus, the possible states (and their meaning) of a
   * `MemoryLocation` are as follows:
   *
   *   content = value
   *   marker = null
   *   Meaning: this is the initial state, or the one
   *   after full cleanup. A new descriptor can be freely
   *   stored, but before that, a new mark also needs to be
   *   installed.
   *
   *   content = value
   *   marker = empty
   *   Meaning: a descriptor previously was in the location,
   *   but it was already replaced. The empty marker can be
   *   freely deleted or replaced by a new one.
   *
   *   content = value
   *   marker = full
   *   Meaning: a new marker have been installed, but then the
   *   descriptor wasn't installed (due to a race; see comment at
   *   the end of `tryWord`); the marker can be reused next time.
   *
   *   content = descriptor
   *   marker = null
   *   Meaning: the descriptor is not in use any more, and the
   *   `WeakReference` object was also cleared up; otherwise
   *   see below.
   *
   *   content = descriptor
   *   marker = empty
   *   Meaning: the descriptor is not in use any more, and can
   *   be replaced by the final value, or another descriptor
   *   (but before installing a new descriptor, a new marker must
   *   be also installed).
   *
   *   content = descriptor
   *   marker = full
   *   Meaning: the descriptor is (possibly) in use, and cannot be
   *   replaced, except by another descriptor with the
   *   same mark.
   *
   *
   * ### Versions ###
   *
   * To provide opacity, we need to validate previously read
   * values when we read from a new ref. To be able to do this
   * quickly, refs also store their version. The EMCAS impl.
   * has a global commit counter (commit-ts, commit version),
   * which is a `Long` (a few values are reserved and have a special
   * meaning, see `Version.isValid`). The version of a ref is the
   * commit version which last changed the ref (or `Version.Start` if it
   * was never changed). This system is based on the one in
   * SwissTM (https://infoscience.epfl.ch/record/136702/files/pldi127-dragojevic.pdf),
   * although this implementation is lock-free.
   *
   * On top of this version-based validation system, we implement
   * an optimization from the paper "Commit Phase in Timestamp-based STM"
   * (https://web.archive.org/web/20220302005715/https://www.researchgate.net/profile/Zoran-Budimlic/publication/221257687_Commit_phase_in_timestamp-based_stm/links/004635254086f87ab9000000/Commit-phase-in-timestamp-based-stm.pdf).
   * We allow ops to *share* a commit-ts (if they do not conflict).
   * Our implementation is a lock-free version of algorithm "V1" from
   * the paper.
   *
   * Versions (both of a ref and the global one) are always
   * monotonically increasing.
   *
   * To support reading/writing of versions, a ref has the
   * `unsafeGetVersionVolatile` and `unsafeCasVersionVolatile`
   * methods. However, the version accessible by these is
   * only correct, if the ref currently stores a value (and not
   * a descriptor, see above). If it stores a descriptor, the
   * current (logical) version is the one in the descriptor
   * (the old or new version, depending on the state of the op).
   * Thus, refs can have the following states:
   *
   *   content = value
   *   version = any valid version
   *   Meaning: the currently physically stored version is
   *   the same as the logical version.
   *
   *   content = descriptor with parent status `EmcasStatus.Active`
   *   version = (don't care)
   *   Meaning: the logical version is the OLD version in the desc
   *
   *   content = descriptor with parent status `EmcasStatus.Successful`
   *   version = (don't care)
   *   Meaning: the logical version is the NEW version in the desc
   *
   *   content = descriptor with any other parent status
   *   version = (don't care)
   *   Meaning: the logical version is the OLD version in the desc
   */

  // TODO: this is unused (only 0 or non-0 matters)
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
  private[choam] final def readValue[A](ref: MemoryLocation[A], ctx: EmcasThreadContext, replace: Int): HalfWordDescriptor[A] = {
    @tailrec
    def go(mark: AnyRef, ver1: Long): HalfWordDescriptor[A] = {
      ref.unsafeGetVolatile() match {
        case wd: WordDescriptor[_] =>
          if (mark eq null) {
            // not holding it yet
            val weakref = ref.unsafeGetMarkerVolatile()
            val m = if (weakref ne null) weakref.get() else null
            if (m ne null) {
              // we're holding it, re-read the descriptor:
              go(mark = m, ver1 = ver1)
            } else { // m eq null (from either a cleared or a removed weakref)
              // descriptor can be detached
              val parentStatus = wd.parent.getStatus()
              if (parentStatus == EmcasStatus.Active) {
                // active op without a mark: this can
                // happen if a thread died during an op;
                // we help the active op, then retry ours:
                MCAS(wd.parent, ctx = ctx)
                go(mark = null, ver1 = ver1)
              } else { // finalized op
                val successful = (parentStatus == EmcasStatus.Successful)
                val a = if (successful) wd.cast[A].nv else wd.cast[A].ov
                val currVer = if (successful) wd.newVersion else wd.oldVersion
                // marker is null, so we can replace the descriptor:
                this.maybeReplaceDescriptor[A](
                  ref,
                  wd.cast[A],
                  a,
                  weakref = weakref,
                  replace = replace,
                  currentVersion = currVer,
                )
                HalfWordDescriptor(ref, ov = a, nv = a, version = currVer)
              }
            }
          } else { // mark ne null
            // OK, we're already holding the descriptor
            val parentStatus = wd.parent.getStatus()
            if (parentStatus == EmcasStatus.Active) {
              MCAS(wd.parent, ctx = ctx) // help the other op
              go(mark = mark, ver1 = ver1) // retry
            } else { // finalized
              val successful = (parentStatus == EmcasStatus.Successful)
              val a = if (successful) wd.cast[A].nv else wd.cast[A].ov
              val currVer = if (successful) wd.newVersion else wd.oldVersion
              HalfWordDescriptor(ref, ov = a, nv = a, version = currVer)
            }
          }
        case a =>
          val ver2 = ref.unsafeGetVersionVolatile()
          if (ver1 == ver2) {
            HalfWordDescriptor(ref, ov = a, nv = a, version = ver1)
          } else {
            go(mark = null, ver1 = ver2)
          }
      }
    }

    go(mark = null, ver1 = ref.unsafeGetVersionVolatile())
  }

  private[this] final def maybeReplaceDescriptor[A](
    ref: MemoryLocation[A],
    ov: WordDescriptor[A],
    nv: A,
    weakref: WeakReference[AnyRef],
    replace: Int,
    currentVersion: Long,
  ): Unit = {
    if (replace != 0) {
      replaceDescriptor[A](ref, ov, nv, weakref, currentVersion)
    }
  }

  private[this] final def replaceDescriptor[A](
    ref: MemoryLocation[A],
    ov: WordDescriptor[A],
    nv: A,
    weakref: WeakReference[AnyRef],
    currentVersion: Long,
  ): Unit = {
    // *Before* replacing a finalized descriptor, we
    // must write back the current version into the
    // ref. (If we'd just replace the descriptor
    // then we'd have an invalid (possibly really old)
    // version.) We use CAS to write the version; this way
    // if another thread starts and finishes another op,
    // we don't overwrite the newer version. (Versions
    // are always monotonically increasing.)
    assert(currentVersion >= ov.oldVersion)
    val currentInRef = ref.unsafeGetVersionVolatile()
    if (currentInRef < currentVersion) {
      // TODO: we could use an `unsafeCmpxchgVersionVolatile` here:
      if (!ref.unsafeCasVersionVolatile(currentInRef, currentVersion)) {
        // concurrent write, but re-check to be sure:
        assert(ref.unsafeGetVersionVolatile() >= currentVersion)
        // TODO: ^--- This assertion failed once (in BoundedQueueBench);
        // TODO: figure out, why that could've happened.
      } // else: successfully updated version
    } // else: either a concurrent write to newer version, or is already correct

    // We replace the descriptor with the final value.
    // If this CAS fails, someone else might've
    // replaced the desc with the final value, or
    // maybe started another operation; in either case,
    // there is nothing to do here.
    ref.unsafeCasVolatile(ov.castToData, nv)
    // Possibly also clean up the weakref:
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

  // TODO: this could have an optimized version, without creating a hwd
  private[mcas] final def readDirect[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): A = {
    val hwd = readIntoHwd(ref, ctx)
    hwd.nv
  }

  private[mcas] final def readIntoHwd[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): HalfWordDescriptor[A] = {
    readValue(ref, ctx, Emcas.replacePeriodForReadValue)
  }

  @tailrec
  private[mcas] final def readVersion[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): Long = {
    val ver1 = ref.unsafeGetVersionVolatile()
    ref.unsafeGetVolatile() match {
      case wd: WordDescriptor[_] =>
        val p = wd.parent
        val s = p.getStatus()
        if (s == EmcasStatus.Active) {
          // help and retry:
          MCAS(p, ctx = ctx)
          readVersion(ref, ctx)
        } else if (s == EmcasStatus.Successful) {
          wd.newVersion
        } else { // Failed
          wd.oldVersion
        }
      case _ => // value
        val ver2 = ref.unsafeGetVersionVolatile()
        if (ver1 == ver2) ver1
        else readVersion(ref, ctx) // retry
    }
  }

  // Listing 3 in the paper:

  /**
   * Performs an MCAS operation.
   *
   * @param desc: The main descriptor.
   * @param ctx: The [[EMCASThreadContext]] of the current thread.
   */
  def MCAS(desc: EmcasDescriptor, ctx: EmcasThreadContext): Long = {

    @tailrec
    def tryWord[A](wordDesc: WordDescriptor[A]): Long = {
      var content: A = nullOf[A]
      var value: A = nullOf[A]
      var weakref: WeakReference[AnyRef] = null
      var mark: AnyRef = null
      var version: Long = wordDesc.address.unsafeGetVersionVolatile()
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
                if (parentStatus == EmcasStatus.Active) {
                  // active op without a mark: this can
                  // happen if a thread died during an op
                  if (wd eq wordDesc) {
                    // this is us!
                    // already points to the right place, early return:
                    return EmcasStatus.Successful // scalafix:ok
                  } else {
                    // we help the active op (who is not us),
                    // then continue with another iteration:
                    MCAS(wd.parent, ctx = ctx)
                  }
                } else { // finalized op
                  if (parentStatus == EmcasStatus.Successful) {
                    value = wd.cast[A].nv
                    version = wd.newVersion
                    go = false
                  } else { // Failed
                    value = wd.cast[A].ov
                    version = wd.oldVersion
                    go = false
                  }
                }
              }
            } else { // mark ne null
              if (wd eq wordDesc) {
                // already points to the right place, early return:
                return EmcasStatus.Successful // scalafix:ok
              } else {
                // At this point, we're sure that `wd` belongs to another op
                // (not `desc`), because otherwise it would've been equal to
                // `wordDesc` (we're assuming that any WordDescriptor only
                // appears at most once in an MCASDescriptor).
                val parentStatus = wd.parent.getStatus()
                if (parentStatus == EmcasStatus.Active) {
                  MCAS(wd.parent, ctx = ctx) // help the other op
                  // Note: we're not "helping" ourselves for sure, see the comment above.
                  // Here, we still don't have the value, so the loop must retry.
                } else if (parentStatus == EmcasStatus.Successful) {
                  value = wd.cast[A].nv
                  version = wd.newVersion
                  go = false
                } else { // Failed
                  value = wd.cast[A].ov
                  version = wd.oldVersion
                  go = false
                }
              }
            }
          case a =>
            value = a
            val version2 = wordDesc.address.unsafeGetVersionVolatile()
            if (version == version2) {
              // ok, we have a version that belongs to `value`
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
            } else {
              // couldn't read consistent versions for
              // the value, will try again; start from
              // the latest version we've read:
              version = version2
            }
        }
      }

      // just to be safe:
      assert((mark eq null) || (mark eq weakref.get()))
      assert(Version.isValid(version))

      if (!equ(value, wordDesc.ov)) {
        // Expected value is different:
        EmcasStatus.FailedVal
      } else if (version != wordDesc.oldVersion) {
        // The expected value is the same,
        // but the expected version isn't:
        EmcasStatus.FailedVal
      } else if (desc.getStatus() != EmcasStatus.Active) {
        // we have been finalized (by a helping thread), no reason to continue
        EmcasStatus.Break
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
          EmcasStatus.Successful
        } else {
          // either we couldn't install the new mark, or
          // the CAS on the `Ref` failed; in either case,
          // we'll retry:
          Reference.reachabilityFence(mark)
          tryWord(wordDesc)
        }
      }
    } // tryWord

    @tailrec
    def go(words: java.util.Iterator[WordDescriptor[_]]): Long = {
      if (words.hasNext) {
        val word = words.next()
        if (word ne null) {
          val twr = tryWord(word)
          if (twr == EmcasStatus.Successful) go(words)
          else twr
        } else {
          // Another thread already finalized the descriptor,
          // and cleaned up this word descriptor (hence the `null`);
          // thus, we should not continue:
          assert(desc.getStatus() != EmcasStatus.Active)
          EmcasStatus.Break
        }
      } else {
        EmcasStatus.Successful
      }
    }

    assert(!desc.hasVersionCas) // just to be sure
    val r = go(desc.wordIterator())
    if (r == EmcasStatus.Break) {
      // someone else finalized the descriptor, we must read its status:
      desc.getStatus()
    } else {
      assert(r != EmcasStatus.Active)
      if (r == EmcasStatus.Successful) {
        // successfully installed all descriptors (~ACQUIRE)
        // we'll need a new commit-ts:
        val ourTs = retrieveFreshTs()
        val wit = desc.cmpxchgCommitVer(ourTs)
        if (wit != Version.None) {
          // someone else already did it
          assert(wit >= desc.newVersion)
        } else {
          // ok, we did it
          assert(ourTs >= desc.newVersion)
        }
      }
      val witness: Long = desc.cmpxchgStatus(EmcasStatus.Active, r)
      if (witness == EmcasStatus.Active) {
        // we finalized the descriptor
        // TODO: we should consider emptying the
        // TODO: array of WDs in `desc` now, to help GC
        r
      } else {
        // someone else already finalized the descriptor, we return its status:
        witness
      }
    }
  }

  private[this] final def retrieveFreshTs(): Long = {
    val ts1 = this.global.commitTs.get()
    val ts2 = this.global.commitTs.get()
    if (ts1 != ts2) {
      // we've observed someone else changing the version:
      assert(ts2 > ts1)
      ts2
    } else {
      // we try to increment it:
      val candidate = ts1 + 1L
      val ctsWitness = this.global.commitTs.compareAndExchange(ts1, candidate)
      if (ctsWitness == ts1) {
        // ok, successful CAS:
        candidate
      } else {
        // failed CAS, but this means that someone else incremented it:
        assert(ctsWitness > ts1)
        ctsWitness
      }
    }
  }

  final override def currentContext(): EmcasThreadContext =
    this.global.currentContext()

  private[choam] final override def isThreadSafe =
    true

  private[mcas] final def tryPerformInternal(desc: HalfEMCASDescriptor, ctx: EmcasThreadContext): Long = {
    tryPerformDebug(desc = desc, ctx = ctx)
  }

  private[mcas] final def tryPerformDebug(desc: HalfEMCASDescriptor, ctx: EmcasThreadContext): Long = {
    if (desc.nonEmpty) {
      val fullDesc = EmcasDescriptor.prepare(desc)
      val res = Emcas.MCAS(desc = fullDesc, ctx = ctx)
      assert((res == EmcasStatus.Successful) || (res == EmcasStatus.FailedVal))
      res
    } else {
      EmcasStatus.Successful
    }
  }

  private[choam] final override def getRetryStats(): mcas.MCAS.RetryStats = {
    this.global.getRetryStats()
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
          if (wd.parent.getStatus() == EmcasStatus.Active) {
            // CAS in progress, retry
          } else {
            // CAS finalized, but no cleanup yet, read and retry
            Emcas.readDirect(ref, ctx = ctx)
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
