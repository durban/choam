/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.ref.{ Reference, WeakReference }

/**
 * Efficient Multi-word Compare and Swap (EMCAS):
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private[mcas] final class Emcas(
  private[choam] final override val osRng: OsRng,
) extends GlobalContext { global =>

  require(osRng ne null)

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
   * A finished EMCAS operation leaves `EmcasWordDesc`s
   * in the `MemoryLocation`s it touches. These can only
   * be removed (i.e., detached, replaced by the final value
   * or a new descriptor) if no other thread (helpers, or the
   * original thread, in case a helper completes the op) uses
   * the `EmcasWordDesc` any more. If they are removed while
   * still in use, that can cause ABA-problems. (Also, if
   * existing descriptor objects would be reused, it would cause
   * other problems too. However, currently only fresh
   * descriptors are used.)
   *
   * A specific scenario which would cause a problem:
   * - T1 starts an op [(r1, "a", "b"), (r2, "x", "y")]
   * - Checks version and current value of r1, ok, so it
   *   installs the WD into r1.
   * - Checks version and current value of r2, ok.
   * - T1 "pauses" right before the CAS to install WD
   *   into r2.
   * - T2 helps, finalizes the op.
   * - T2 executes unrelated op, which changes r2 back
   *   to "x", finalizes.
   * - T2 detaches the WD from r2, so now r2="x".
   * - T1 continues execution, executes the CAS to
   *   install WD into r2, which succeeds (since r2="x").
   *   But this is incorrect, the version of r2 changed
   *   since it checked.
   * So versions by themselves don't save us from ABA-problems,
   * because we can't CAS them together with the values.
   * TODO: We could, if we had a double-word-CAS (or would implement
   * TODO: something like RDCSS or GCAS).
   * But instead we use marks...
   *
   * To guarantee that in-use descriptors are never replaced,
   * every thread (the original and any helpers) must always
   * hold a strong reference to the "mark(er)" associated with
   * the descriptor. This way, if the marker is collected by
   * the JVM GC, we can be sure, that the corresponding descriptor
   * can be replaced.
   *
   * Markers are manipulated with the `unsafeGetMarkerV` and
   * `unsafeCasMarkerV` methods of `MemoryLocation`. The
   * marker of a `MemoryLocation` can be in the following states:
   *
   *   null - `unsafeGetMarkerV` returns null, i.e.,
   *   not even a `WeakReference` object exists.
   *
   *   empty - `unsafeGetMarkerV` returns an empty,
   *   i.e., cleared `WeakReference` object.
   *
   *   full - `unsafeGetMarkerV` returns a full, i.e.,
   *   not cleared `WeakReference` object.
   *
   * The other methods (e.g., `unsafeGetV`) manipulate the "content"
   * of the `MemoryLocation`. If the content is a "value", that can be
   * freely replaced (with a CAS) by a descriptor during an operation.
   * (But the new descriptor must have a mark.) The content can have the
   * following  possible states:
   *
   *   value - a user-supplied value (possibly including `null`);
   *   this state includes anything that is not a `EmcasWordDesc`.
   *
   *   descriptor - a (non-null) `EmcasWordDesc` object.
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
   *   replaced, except CAS-ing another descriptor with the
   *   same mark in its place.
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
   * was never changed). This system is based on the one in SwissTM
   * (https://infoscience.epfl.ch/server/api/core/bitstreams/6b454d6b-0ae9-4b37-b341-6e2d092aef8e/content),
   * although this implementation is lock-free.
   *
   * On top of this version-based validation system, we implement
   * an optimization from the paper "Commit Phase Variations in Timestamp-based STM"
   * (https://repository.rice.edu/server/api/core/bitstreams/ec929767-5e4b-4c8e-9704-c649bf6328c9/content).
   * We allow ops to *share* a commit-ts (if they do not conflict).
   * Our implementation is a lock-free version of algorithm "V1" from
   * the paper.
   *
   * TODO: Consider using V4 (instead of V1) from the Commit Phase paper.
   *
   * The proof of correctness in the Commit Phase paper needs some changes for our system:
   * - The proof of "Lemma 1" considers three possible scenarios:
   *   - The first one is not possible for us not due to read-locking,
   *     but because at t₂ the validation performed by T₁ would help
   *     T₂ finish, and then the validation would fail (since O is
   *     already in the log of T₁).
   *   - The second one works the same way, except that if T₂ hasn't
   *     committed yet, the OPEN by T₁ will help it commit.
   *   - In the third scenario, if t₄ < t₅, then the OPEN at t₄ will
   *     help the commit (so in fact t₄ = t₅), and revalidate T₁, so
   *     that's fine. The other 2 cases work essentially the same.
   *     (Note: the third scenario in the paper seems to have a typo,
   *     "t₃ ≤ t₄ < t₂" should be "t₂ < t₄ ≤ t₃".)
   * - The proof of "Theorem 1" works essentially the same.
   *
   * Versions (both of a ref and the global one) are always
   * monotonically increasing.
   *
   * To support reading/writing of versions, a ref has the
   * `unsafeGetVersionV` and `unsafeCmpxchgVersionV`
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
   *   content = descriptor with parent status `McasStatus.Active`
   *   version = (don't care)
   *   Meaning: the logical version is the OLD version in the desc
   *            (although, we never use the old version in this case,
   *            instead we always help the active op; this is
   *            important to allow version sharing); this is an op
   *            which is still active
   *
   *   content = descriptor with parent status `McasStatus.FailedVal`
   *   version = (don't care)
   *   Meaning: the logical version is the OLD version in the desc;
   *            this is an op which already failed
   *
   *   content = descriptor with any parent status `s` for which
   *             `EmcasStatus.isSuccessful(s)` is true
   *   version = (don't care)
   *   Meaning: the logical version is the NEW version in the desc,
   *            which is stored indirectly: the version is the parent
   *            status itself; this is a successful op
   */

  // TODO: Most accesses here (and elsewhere) are volatile;
  // TODO: figure out if we can use acq/rel, and still remain
  // TODO: correct.

  // Listing 2 in the EMCAS paper:

  /**
   * A specialized version of `readInternal` from the EMCAS paper
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
   * @param replace: Pass `false` to not do any replacing/clearing.
   */
  private[this] final def readValue[A](ref: MemoryLocation[A], ctx: EmcasThreadContext, replace: Boolean): LogEntry[A] = {
    @tailrec
    def go(mark: AnyRef, ver1: Long): LogEntry[A] = {
      ref.unsafeGetV() match {
        case wd: EmcasWordDesc[_] =>
          if (mark eq null) {
            // not holding it yet
            val weakref = ref.unsafeGetMarkerV()
            val m = if (weakref ne null) weakref.get() else null
            if (m ne null) {
              // we're holding it, re-read the descriptor:
              go(mark = m, ver1 = ver1)
            } else { // m eq null (from either a cleared or a removed weakref)
              // descriptor can be detached
              val parent = wd.parent
              val parentStatus = parent.getStatusV()
              if (parentStatus == McasStatus.Active) {
                // active op without a mark: this can
                // happen if a thread died during an op;
                // we help the active op, then retry ours:
                helpMCASnoMCAS(parent, ctx = ctx)
                go(mark = null, ver1 = ver1)
              } else { // finalized op
                val successful = (parentStatus != McasStatus.FailedVal) && (parentStatus != EmcasStatus.CycleDetected)
                val a = if (successful) wd.cast[A].nv else wd.cast[A].ov
                val currVer = if (successful) parentStatus else wd.oldVersion
                // marker is null, so we can replace the descriptor:
                this.maybeReplaceDescriptor[A](
                  ref,
                  wd.cast[A],
                  a,
                  weakref = weakref,
                  replace = replace,
                  currentVersion = currVer,
                )
                LogEntry(ref, ov = a, nv = a, version = currVer)
              }
            }
          } else { // mark ne null
            // OK, we're already holding the descriptor
            val parent = wd.parent
            val parentStatus = parent.getStatusV()
            if (parentStatus == McasStatus.Active) {
              helpMCASnoMCAS(parent, ctx = ctx) // help the other op
              go(mark = mark, ver1 = ver1) // retry
            } else { // finalized
              val successful = (parentStatus != McasStatus.FailedVal) && (parentStatus != EmcasStatus.CycleDetected)
              val a = if (successful) wd.cast[A].nv else wd.cast[A].ov
              val currVer = if (successful) parentStatus else wd.oldVersion
              Reference.reachabilityFence(mark)
              LogEntry(ref, ov = a, nv = a, version = currVer)
            }
          }
        case a =>
          val ver2 = ref.unsafeGetVersionV()
          if (ver1 == ver2) {
            LogEntry(ref, ov = a, nv = a, version = ver1)
          } else {
            go(mark = null, ver1 = ver2)
          }
      }
    }

    go(mark = null, ver1 = ref.unsafeGetVersionV())
  }

  private[this] final def maybeReplaceDescriptor[A](
    ref: MemoryLocation[A],
    ov: EmcasWordDesc[A],
    nv: A,
    weakref: WeakReference[AnyRef],
    replace: Boolean,
    currentVersion: Long,
  ): Unit = {
    if (replace) {
      replaceDescriptor[A](ref, ov, nv, weakref, currentVersion)
    }
  }

  private[this] final def replaceDescriptor[A](
    ref: MemoryLocation[A],
    ov: EmcasWordDesc[A],
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
    _assert(currentVersion >= ov.oldVersion)
    val currentInRef = ref.unsafeGetVersionV()
    if (currentInRef < currentVersion) {
      val wit = ref.unsafeCmpxchgVersionV(currentInRef, currentVersion)
      if (wit == currentInRef) {
        // We've successfully updated the version.
        // Now we'll replace the descriptor with the final value.
        // If this CAS fails, someone else might've
        // replaced the desc with the final value, or
        // maybe started another operation; in either case,
        // there is nothing to do here.
        ref.unsafeCmpxchgR(ov.castToData, nv) : Unit
        // Possibly also clean up the weakref:
        cleanWeakRef(ref, weakref)
      } else {
        _assert(wit >= currentVersion)
        // concurrent write, no need to replace the
        // descriptor (see the comment below)
      }
    } else if (currentInRef == currentVersion) {
      // version is already correct, but we'll still replace the desc;
      // we don't care if this fails, see above:
      ref.unsafeCmpxchgR(ov.castToData, nv) : Unit
      cleanWeakRef(ref, weakref)
    } // else:
    // either a concurrent write to a newer version, in which
    // case there is no need to replace the descriptor, as
    // the newer operation will install a newer descriptor;
    // or it is already correct, in which case there is a
    // concurrent `replaceDescriptor` going on, so we let
    // that one win and replace the descriptor
  }

  private[this] final def cleanWeakRef[A](ref: MemoryLocation[A], weakref: WeakReference[AnyRef]): Unit = {
    if (weakref ne null) {
      _assert(weakref.get() eq null)
      // We also delete the (now empty) `WeakReference`
      // object, to help the GC. If this CAS fails,
      // that means a new op already installed a new
      // weakref; nothing to do here.
      ref.unsafeCmpxchgMarkerR(weakref, null) : Unit
    }
  }

  // TODO: this could have an optimized version, without creating a hwd
  private[mcas] final def readDirect[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): A = {
    val hwd = readIntoHwd(ref, ctx)
    hwd.nv
  }

  private[mcas] final def readIntoHwd[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): LogEntry[A] = {
    readValue(ref, ctx, replace = true)
  }

  private[mcas] final def readVersion[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): Long = {
    val v = readVersionInternal(ref, ctx, forMCAS = false, seen = 0L, instRo = false)
    _assert(VersionFunctions.isValid(v))
    v
  }

  @tailrec
  private[this] final def readVersionInternal[A](
    ref: MemoryLocation[A],
    ctx: EmcasThreadContext,
    forMCAS: Boolean,
    seen: Long,
    instRo: Boolean,
  ): Long = {
    val ver1 = ref.unsafeGetVersionV()
    // Note: the version we just read might not
    // be the actual version; if the ref contains
    // a descriptor, that descriptor will contain
    // the correct version.
    ref.unsafeGetV() match {
      case wd: EmcasWordDesc[_] =>
        val parent = wd.parent
        val s = parent.getStatusV()
        if (s == McasStatus.Active) {
          // help:
          if (forMCAS) {
            if (helpMCASforMCAS(parent, ctx = ctx, seen = seen, instRo = instRo)) {
              // Note: `forMCAS` is true here, so we can return a reserved version
              EmcasStatus.CycleDetected
            } else {
              // retry:
              readVersionInternal(ref, ctx, forMCAS = forMCAS, seen = seen, instRo = instRo)
            }
          } else {
            helpMCASnoMCAS(parent, ctx = ctx)
            // retry:
            readVersionInternal(ref, ctx, forMCAS = forMCAS, seen = seen, instRo = instRo)
          }
        } else if ((s == McasStatus.FailedVal) || (s == EmcasStatus.CycleDetected)) {
          wd.oldVersion
        } else { // successful
          s
        }
      case _ => // value
        // Note: we don't actually know that the `ver1`
        // we read previously belongs to the value we
        // just read (the value could've been written
        // after we read `ver1`), so we must re-read
        // the version.
        val ver2 = ref.unsafeGetVersionV()
        if (ver1 == ver2) {
          // OK, as versions are monotonically increasing
          // (and we increase the version before detaching
          // a descriptor), we can be sure that the version
          // is valid (and belongs to the value, although
          // we don't need the value here):
          ver1
        } else {
          // some other op (or `readValue`) changed the
          // version (and completed), so we must retry:
          readVersionInternal(ref, ctx, forMCAS = forMCAS, seen = seen, instRo = instRo) // retry
        }
    }
  }

  /** Returns `true` iff the helper MCAS should retry (due to a cycle) */
  private[this] final def helpMCASforMCAS(
    desc: EmcasDescriptor,
    ctx: EmcasThreadContext,
    seen: Long,
    instRo: Boolean, // the helper's `instRo`!
  ): Boolean = {
    if (MCAS(desc, ctx, seen) == EmcasStatus.CycleDetected) {
      if (instRo) {
        // we don't care that the op we helped has
        // a cycle, we certainly don't have one
        false
      } else {
        // cycle detected, and the helper could
        // be part of the cycle, so it should retry:
        true
      }
    } else {
      // no cycle detected:
      false
    }
  }

  private[this] final def helpMCASnoMCAS(desc: EmcasDescriptor, ctx: EmcasThreadContext): Unit = {
    // if we're NOT called from an ongoing MCAS,
    // we don't really care if there is a cycle
    // detected; we just want the descriptor
    // out of the way to do our thing (whoever
    // started the op which got into the cycle
    // WILL care, and will retry):
    helpMCASforMCAS(desc, ctx, seen = 0L, instRo = true) : Unit
  }

  /**
   * Performs an MCAS operation ("Listing 3" in the EMCAS paper).
   *
   * @param desc The main descriptor.
   * @param ctx The [[EMCASThreadContext]] of the current thread.
   * @param seen A Bloom filter, which contains the `EmcasDescriptor`s
   *             we have seen so far (during the recursive helping).
   *             If it (seemingly) contains `desc`, then we return
   *             with `CycleDetected`. Otherwise we add `desc` to the
   *             set for further helping. (If `desc.instRo` is `true`,
   *             `desc` is excluded from this cycle detection, because
   *             it is certainly not part of a cycle.)
   * @return The result of the MCAS operation on `desc`: the new version
   *         iff it was successful, `FailedVal` iff it failed, and `CycleDetected`
   *         iff a cycle was detected during helping (can only happen if
   *         `desc.instRo` is `false`). Postcondition: `desc` have been
   *         already finalized with the result which is returned.
   */
  private[this] final def MCAS(desc: EmcasDescriptor, ctx: EmcasThreadContext, seen: Long): Long = {

    val instRo = desc.instRo

    @tailrec
    def tryWord[A](wordDesc: EmcasWordDesc[A], newSeen: Long): Long = {
      var content: A = nullOf[A]
      var value: A = nullOf[A]
      var weakref: WeakReference[AnyRef] = null
      var mark: AnyRef = null
      val address = wordDesc.address
      var version: Long = address.unsafeGetVersionV()
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
        content = address.unsafeGetV()
        content match {
          case wd: EmcasWordDesc[_] =>
            if (mark eq null) {
              // not holding it yet
              weakref = address.unsafeGetMarkerV()
              mark = if (weakref ne null) weakref.get() else null
              if (mark ne null) {
                // continue with another iteration, and re-read the
                // descriptor, while holding the mark
              } else { // mark eq null
                // TODO: what if between reading `wd` and the mark, a lot of things happened...?
                // the old descriptor is unused, could be detached
                val parent = wd.parent
                val parentStatus = parent.getStatusV()
                if (parentStatus == McasStatus.Active) {
                  // active op without a mark: this can
                  // happen if a thread died during an op
                  if (wd eq wordDesc) {
                    // this is us!
                    // already points to the right place, early return:
                    return McasStatus.Successful // scalafix:ok
                  } else {
                    // we help the active op (who is not us)
                    if (helpMCASforMCAS(parent, ctx = ctx, seen = newSeen, instRo = instRo)) {
                      // oops, we'll have to finalize ourselves with CycleDetected too
                      // TODO: Do we really have to? The other one was finalized, doesn't that break the cycle?
                      return EmcasStatus.CycleDetected // scalafix:ok
                    } // else: then continue with another iteration
                  }
                } else { // finalized op
                  if ((parentStatus == McasStatus.FailedVal) || (parentStatus == EmcasStatus.CycleDetected)) {
                    value = wd.cast[A].ov
                    version = wd.oldVersion
                  } else { // successful
                    value = wd.cast[A].nv
                    version = parentStatus
                  }
                  go = false
                }
              }
            } else { // mark ne null
              if (wd eq wordDesc) {
                // this is us!
                // already points to the right place, early return:
                return McasStatus.Successful // scalafix:ok
              } else {
                // At this point, we're sure that `wd` belongs to another op
                // (not `desc`), because otherwise it would've been equal to
                // `wordDesc` (we're assuming that any EmcasWordDesc only
                // appears at most once in an EmcasDescriptor).
                val parent = wd.parent
                val parentStatus = parent.getStatusV()
                if (parentStatus == McasStatus.Active) {
                  // Help the other op; note: we're not "helping" ourselves
                  // for sure, see the comment above.
                  if (helpMCASforMCAS(parent, ctx = ctx, seen = newSeen, instRo = instRo)) {
                    // oops, we'll have to finalize ourselves with CycleDetected too
                    // TODO: Do we really have to? The other one was finalized, doesn't that break the cycle?
                    return EmcasStatus.CycleDetected // scalafix:ok
                  } // else: we helped, but we still don't have the value, so the loop must retry
                } else if ((parentStatus == McasStatus.FailedVal) || (parentStatus == EmcasStatus.CycleDetected)) {
                  value = wd.cast[A].ov
                  version = wd.oldVersion
                  go = false
                } else { // successful
                  value = wd.cast[A].nv
                  version = parentStatus
                  go = false
                }
              }
            }
          case a =>
            value = a
            val version2 = address.unsafeGetVersionV()
            if (version == version2) {
              // ok, we have a version that belongs to `value`
              go = false
              weakref = address.unsafeGetMarkerV()
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
      _assert(((mark eq null) || (mark eq weakref.get())) && VersionFunctions.isValid(version))

      val wordDescOv = wordDesc.ov
      if (equ(wordDescOv, EmcasDescriptorBase.CLEARED)) {
        Reference.reachabilityFence(mark)
        // we have been finalized (by a helping thread), no reason to continue
        EmcasStatus.Break
      } else if (!equ(value, wordDescOv)) {
        Reference.reachabilityFence(mark)
        // Expected value is different:
        McasStatus.FailedVal
      } else if (version != wordDesc.oldVersion) {
        Reference.reachabilityFence(mark)
        // The expected value is the same,
        // but the expected version isn't:
        McasStatus.FailedVal
      } else if (desc.getStatusV() != McasStatus.Active) {
        Reference.reachabilityFence(mark)
        // we have been finalized (by a helping thread), no reason to continue
        EmcasStatus.Break
      } else {
        // before installing our descriptor, make sure a valid mark exists:
        val weakRefOk = if (mark eq null) {
          _assert((weakref eq null) || (weakref.get() eq null))
          // there was no old descriptor, or it was already unused;
          // we'll need a new mark:
          mark = ctx.getReusableMarker()
          val weakref2 = ctx.getReusableWeakRef()
          _assert(weakref2.get() eq mark)
          address.unsafeCasMarkerV(weakref, weakref2)
          // if this fails, we'll retry, see below
        } else {
          // we have a valid mark from reading
          true
        }
        // If *right now* (after the CAS above), another thread, which
        // started reading before we installed a new weakref above,
        // finishes its read, and detaches the *previous* descriptor
        // (since we haven't installed ours yet, and that one was unused);
        // then the following CAS will fail (not a problem), and
        // on our next retry, we may see a ref with a value *and*
        // a non-empty weakref (but this case is also handled, see above).
        if (weakRefOk && address.unsafeCasV(content, wordDesc.castToData)) {
          Reference.reachabilityFence(mark)
          McasStatus.Successful
        } else {
          // either we couldn't install the new mark, or
          // the CAS on the `Ref` failed; in either case,
          // we'll retry:
          Reference.reachabilityFence(mark)
          tryWord(wordDesc, newSeen)
        }
      }
    } // tryWord

    def acquire(words: Array[WdLike[?]], newSeen: Long): Long = {
      @tailrec
      def go(words: Array[WdLike[?]], next: Int, len: Int, needsValidation: Boolean): Long = {
        if (next < len) {
          words(next) match {
            case null =>
              // Another thread already finalized the descriptor,
              // and cleaned up this word descriptor (hence the `null`);
              // thus, we should not continue:
              EmcasStatus.Break
            case wd: EmcasWordDesc[_] =>
              _assert(instRo || (!wd.readOnly))
              val twr = tryWord(wd, newSeen)
              _assert(
                (twr == McasStatus.Successful) ||
                (twr == McasStatus.FailedVal) ||
                (twr == EmcasStatus.Break) ||
                (twr == EmcasStatus.CycleDetected)
              )
              if (twr == McasStatus.Successful) {
                go(words, next = next + 1, len = len, needsValidation = needsValidation)
              } else {
                twr
              }
            case wd: LogEntry[_] =>
              // TODO: we could validate `wd` here; we'd still
              // TODO: need to validate after ACQUIRE, but if
              // TODO: it's invalid here, we could abort earlier;
              // TODO: need to experiment to see if it's worth doing...
              // read-only WD, which we don't
              // need to install; continue, but
              // we'll need to revalidate later:
              _assert(wd.readOnly)
              go(words, next = next + 1, len = len, needsValidation = true)
          }
        } else {
          if (needsValidation) {
            // this is ugly, but we use Active to signify that we'll need to validate:
            McasStatus.Active
          } else {
            McasStatus.Successful
          }
        }
      }

      if (words ne null) {
        go(words, next = 0, len = words.length, needsValidation = false)
      } else {
        // Already finalized descriptor, see above
        EmcasStatus.Break
      }
    } // acquire

    def validate(words: Array[WdLike[?]], newSeen: Long): Long = {
      @tailrec
      def go(words: Array[WdLike[?]], next: Int, len: Int): Long = {
        if (next < len) {
          words(next) match {
            case null =>
              // already finalized
              EmcasStatus.Break
            case _: EmcasWordDesc[_] =>
              // this WD have been already installed by `acquire`
              go(words, next = next + 1, len = len)
            case wd: LogEntry[_] =>
              _assert(wd.readOnly)
              // revalidate:
              val currVer = this.readVersionInternal(wd.address, ctx, forMCAS = true, seen = newSeen, instRo = false)
              if (currVer == wd.oldVersion) {
                // OK, continue:
                go(words, next = next + 1, len = len)
              } else if (currVer == EmcasStatus.CycleDetected) {
                EmcasStatus.CycleDetected
              } else {
                // validation failed:
                McasStatus.FailedVal
              }
          }
        } else {
          McasStatus.Successful
        }
      }

      _assert(!instRo)
      if (words ne null) {
        go(words, next = 0, len = words.length)
      } else {
        // already finalized
        EmcasStatus.Break
      }
    } // validate

    def getFinalResultFromHelper(): Long = {
      val readStatus = desc.getStatusA() // optimistic read
      val result = if (readStatus != McasStatus.Active) {
        readStatus
      } else {
        // we don't see it yet, need to force
        // (see the long comment below)
        desc.cmpxchgStatus(McasStatus.Active, McasStatus.FailedVal)
      }
      _assert(
        VersionFunctions.isValid(result) ||
        (result == McasStatus.FailedVal) ||
        (result == EmcasStatus.CycleDetected)
      )
      result
    }

    var seen2: Long = seen
    val r = if (!instRo) {
      // Cycle detection: we need this to preserve
      // lock-freedom. Because if we have 2 EMCAS like
      // this: [(r1, "a", "b"), (r2, "x", "x")]
      // and [(r1, "a", "a"), (r2, "x", "y")],
      // then both can ACQUIRE, and then when
      // revalidating, both would try to help
      // recursively themselves. That's an
      // infinite loop (or possibly a stack overflow).
      BloomFilter64.insertIfAbsent(seen, desc.hashCode) match {
        case 0L =>
          // We've detected a probable cycle, need to fall
          // back to `instRo = true`. Bloom filter is
          // probabilistic, so there is some chance that
          // there is no actual cycle; but falling back
          // doesn't affect correctness, only performance.
          // (And not falling back when needed _would_
          // affect correctness.)
          // Note: we still have to finalize `desc` with
          // the `CycleDetected` result.
          EmcasStatus.CycleDetected
        case bf =>
          // fine, definitely no cycle, we've added `desc` to `seen`
          seen2 = bf
          acquire(desc.getWordDescArrOrNull(), seen2)
      }
    } else {
      // we're installing every WD, no chance of cycles:
      acquire(desc.getWordDescArrOrNull(), seen2)
    }

    _assert(
      (r == McasStatus.Successful) ||
      (r == McasStatus.FailedVal) ||
      (r == EmcasStatus.Break) ||
      (r == EmcasStatus.CycleDetected) ||
      (r == McasStatus.Active) // means we'll need to validate
    )
    if (r == EmcasStatus.Break) {
      // Someone else finalized the descriptor, we must read its status;
      // however, a volatile read is NOT sufficient here, because it
      // might not actually see the finalized status. Indeed, it COULD
      // return `Active`, which is useless here.
      //
      // The reason for this problem is the plain mode (non-volatile) writes
      // to the array elements in `desc` (when clearing with `null`s).
      // Those are not properly synchronized. And a volatile read of status
      // would not save us here: volatile-reading the new value written
      // by a successful volatile-CAS creates a happens-before relationship
      // (JLS 17.4.4.); however, volatile-reading the OLD value does NOT
      // create a "happens-after" relationship. So, here, after plain-reading
      // a `null` from the array, we could volatile-read `Active` (and this
      // actually happens with JCStress).
      //
      // So, instead of a volatile read, we do a volatile-CAS (with
      // `cmpxchgStatus`) which must necessarily fail, but creates the
      // necessary ordering constraints to get the actual current status
      // (as the witness value of the CAS).
      //
      // To see why this is correct, there are 2 (possible) cases: this
      // `cmpxchgStatus` either reads (1) `Active`, or (2) non-`Active`.
      //
      // If (1), it reads `Active`, then we atomically write `FailedVal`,
      // *and* the finalizing CAS in another thread MUST READ this
      // `FailedVal`, as 2 such CAS-es cannot both succeed. Thus, this
      // CAS happens-before the finalizing CAS, which happens-before
      // writing the `null` to the array. As reading `null` happened-before
      // this CAS, reading the `null` happens-before writing it. Which
      // is not allowed (JLS 17.4.5.). Thus, case (1) is actually impossible.
      //
      // So case (2) is what must happen: this `cmpxchgStatus` reads a
      // non-`Active` value as the witness value (which is what we need).
      //
      // (Besides reading `null`, another reason for the `Break` could be
      // that we already volatile-read a non-`Active` status. In that case
      // the `cmpxchgStatus` will also fail, and we will get the final
      // status as the witness. Which is fine.)
      getFinalResultFromHelper()
    } else {
      val r2 = if ((r == McasStatus.Successful) || (r == McasStatus.Active)) {
        val needsValidation = (r == McasStatus.Active)
        _assert((!instRo) || (!needsValidation))
        if (!needsValidation) {
          // successfully installed every descriptor (ACQUIRE)
          // we'll need a new commit-ts, which we will
          // CAS into the descriptor:
          retrieveFreshTs()
        } else {
          // successfully installed all read-write
          // descriptors (ACQUIRE), but still need to
          // validate our read-set (the read-only
          // descriptors, which were not installed):
          val vr = validate(desc.getWordDescArrOrNull(), newSeen = seen2)
          _assert(
            (vr == McasStatus.Successful) ||
            (vr == McasStatus.FailedVal) ||
            (vr == EmcasStatus.Break) ||
            (vr == EmcasStatus.CycleDetected)
          )
          if (vr == EmcasStatus.Break) {
            // we're already finalized, see the long comment above
            EmcasStatus.Break
          } else if (vr == McasStatus.Successful) {
            // validation succeeded; we'll need a new
            // commit-ts, which we will
            // CAS into the descriptor:
            retrieveFreshTs()
          } else if (vr == EmcasStatus.CycleDetected) {
            EmcasStatus.CycleDetected
          } else {
            // validation failed
            McasStatus.FailedVal
          }
        }
      } else {
        r
      }

      if (r2 == EmcasStatus.Break) {
        // we're already finalized, see the long comment above
        getFinalResultFromHelper()
      } else {
        val finalRes = r2
        _assert(
          VersionFunctions.isValid(finalRes) ||
          (finalRes == McasStatus.FailedVal) ||
          (finalRes == EmcasStatus.CycleDetected)
        )
        val witness: Long = desc.cmpxchgStatus(McasStatus.Active, finalRes)
        if (witness == McasStatus.Active) {
          // we finalized the descriptor
          desc.wasFinalized(finalRes)
          if (Consts.statsEnabled) {
            ctx.recordEmcasFinalizedO()
            if (finalRes == EmcasStatus.CycleDetected) {
              // TODO: Note: Our Bloom filter `seen2` isn't necessarily
              // TODO: correct here, since it could be that it wasn't this
              // TODO: op who detected the cycle, but we could have detected
              // TODO: it during helping. This is not a big problem, since
              // TODO: the Bloom filter size is just for statistical/informational
              // TODO: purposes. (We could fix this, if we somehow got back the
              // TODO: filter from helping. But we only get back a `Long`, which
              // TODO: is `CycleDetected` in this case.)
              // We finalized `desc` with a cycle, so record it for stats:
              ctx.recordCycleDetected(BloomFilter64.estimatedSize(seen2))
            }
          }
          finalRes
        } else {
          // someone else already finalized the descriptor, we return its status:
          _assert(
            VersionFunctions.isValid(witness) ||
            (witness == McasStatus.FailedVal) ||
            (witness == EmcasStatus.CycleDetected)
          )
          witness
        }
      }
    }
  } // MCAS

  /**
   * Retrieves the (possibly new) version, which
   * will (tentatively) be the commit-version of
   * the current op.
   *
   * (Note: if we later fail to CAS this version
   * into the status of the descriptor, then it
   * might have a different commit-version. This
   * can happen if another helper thread wins the
   * race and finalizes the op before us.)
   *
   * This method retrieves a "fresh" version number
   * (commit-ts) by possibly incrementing the global
   * version. However, it doesn't necessarily needs
   * to *actually* increment it. It is enough if it
   * *observes* an increment of the version. The
   * act of incrementing could be performed by another
   * thread (even by a completely unrelated op).
   *
   * This is safe, because this method runs *after*
   * all word descriptors in the current op were
   * successfully installed into the refs. This means
   * that any concurrent read of any of the refs
   * would help finalize *us* before doing anything
   * with the ref. (In effect, it is as if we've
   * "acquired" the refs. Of course, since we're
   * lock-free, this will not block anybody, but
   * they will help us first.)
   *
   * This mechanism is the one which allows unrelated
   * (i.e., non-conflicting, disjoint) ops to *share*
   * a commit-version. Unrelated ops can do this
   * safely, because (as explained above) by the
   * time the version incrementing happens, the refs
   * are already "acquired". Related (i.e., conflicting)
   * ops will not share a version number (that would
   * be unsafe). This is guaranteed, because
   * installing 2 conflicting word descriptors into
   * one ref is not possible (the loser helps the
   * winner to finalize, then retries).
   */
  private[this] final def retrieveFreshTs(): Long = {
    val ts1 = global.getCommitTs()
    val ts2 = global.getCommitTs()
    if (ts1 != ts2) {
      // we've observed someone else changing the version:
      _assert(ts2 > ts1)
      ts2
    } else {
      // we try to increment it:
      val candidate = ts1 + Version.Incr
      Predef.assert(VersionFunctions.isValid(candidate)) // detect version overflow
      val ctsWitness = global.cmpxchgCommitTs(ts1, candidate)
      if (ctsWitness == ts1) {
        // ok, successful CAS:
        candidate
      } else {
        // failed CAS, but this means that someone else incremented it:
        _assert(ctsWitness > ts1)
        ctsWitness
      }
    }
  }

  final override def currentContext(): Mcas.ThreadContext =
    global.currentContextInternal()

  final override def isCurrentContext(ctx: Mcas.ThreadContext): Boolean = {
    if (ctx.isInstanceOf[EmcasThreadContext]) {
      val etc = ctx.asInstanceOf[EmcasThreadContext]
      etc.isCurrentContext()
    } else {
      false
    }
  }

  private[choam] final override def isThreadSafe =
    true

  private[choam] final override def hasVersionFailure: Boolean =
    false

  private[mcas] final def tryPerformInternal(desc: AbstractDescriptor, ctx: EmcasThreadContext, optimism: Long): Long = {
    tryPerformDebug(desc = desc, ctx = ctx, optimism = optimism)
  }

  private[mcas] final def tryPerformDebug(desc: AbstractDescriptor, ctx: EmcasThreadContext, optimism: Long): Long = {
    val emcasRes = tryPerformDebug0(desc, ctx, optimism)
    if (EmcasStatusFunctions.isSuccessful(emcasRes)) {
      // `Emcas` stores a version in the descriptor,
      // to signify success; however, here we return
      // a constant, to follow the `Mcas` API:
      McasStatus.Successful
    } else {
      _assert((emcasRes == McasStatus.Successful) || (emcasRes == McasStatus.FailedVal) || (emcasRes == Version.Reserved))
      emcasRes
    }
  }

  /** Note: returns the internal EMCAS result, and NOT the public `Mcas` result! */
  private[mcas] final def tryPerformDebug0(desc: AbstractDescriptor, ctx: EmcasThreadContext, optimism: Long): Long = {
    if (desc.nonEmpty) {
      _assert(!desc.readOnly)
      val instRo = (optimism.toInt : @switch) match {
        case 0 => true
        case 1 => false
        case _ => throw new IllegalArgumentException
      }
      val fullDesc = new EmcasDescriptor(desc, instRo = instRo)
      if (fullDesc.getWordsP() ne null) {
        val res = MCAS(desc = fullDesc, ctx = ctx, seen = 0L)
        if (res == EmcasStatus.CycleDetected) {
          _assert(!instRo)
          // we detected a (possible) cycle, so
          // we'll fall back to the method which
          // is certainly lock free (always installing
          // every WD, even the read-only ones):
          val fallback = fullDesc.fallback
          _assert(fallback.instRo)
          val fbRes = MCAS(fallback, ctx = ctx, seen = 0L)
          _assert(fbRes != EmcasStatus.CycleDetected) // now we can't get CycleDetected
          if (fbRes == McasStatus.FailedVal) {
            // we signal, that previously there WAS a cycle:
            Version.Reserved
          } else {
            fbRes
          }
        } else {
          res
        }
      } else {
        // The `readOnly` status of the `AbstractDescriptor`
        // is only an approximation; if every non-read-only
        // HWD "becomes" read-only, `desc.readOnly` could still
        // be false. We detect this when copying the HAMT
        // into an array, and return a `null` array. This
        // happened here; since the descriptor is read-only,
        // and we validated every read, we're done (i.e.,
        // this is a read-only reaction, we just didn't
        // realize it until now).
        McasStatus.Successful
      }
    } else {
      McasStatus.Successful
    }
  }

  /** Only for testing! */
  @throws[InterruptedException]
  private[emcas] final def spinUntilCleanup[A](ref: MemoryLocation[A], max: Long = Long.MaxValue): A = {
    val ctx = this.currentContextInternal()
    var ctr: Long = 0L
    while (ctr < max) {
      ref.unsafeGetV() match {
        case wd: EmcasWordDesc[_] =>
          if (wd.parent.getStatusV() == McasStatus.Active) {
            // CAS in progress, retry
          } else {
            // CAS finalized, but no cleanup yet, read and retry
            readDirect(ref, ctx = ctx) : Unit
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
          if ((ctr % 1024L) == 0L) {
            if ((ctr % 0x100000L) == 0L) {
              // the GC is really not doing what we
              // want it to, so we do a new allocation,
              // maybe  that causes the GC to run:
              val arr = new java.util.concurrent.atomic.AtomicLongArray(8192)
              arr.compareAndExchange(4321, 0L, 42L)
            } else {
              System.gc()
            }
          } else {
            Thread.sleep(32L)
          }
        }
      }
    }
    nullOf[A]
  }

  // JMX MBean for stats:
  private[this] val registeredObjectName: String = {
    if (Consts.statsEnabled) {
      val objNameStr = f"${GlobalContextBase.emcasJmxStatsNamePrefix}%s-${System.identityHashCode(this)}%08x"
      val objName = new javax.management.ObjectName(objNameStr)
      java.lang.management.ManagementFactory.getPlatformMBeanServer().registerMBean(
        new EmcasJmxStats(this),
        objName,
      )
      objNameStr
    } else {
      null
    }
  }

  private[choam] final override def close(): Unit = {
    if (Consts.statsEnabled) {
      this.registeredObjectName match {
        case null =>
          // in theory this is impossible,
          // but it's too late, as we're
          // in `close`; and we don't
          // really want to throw during
          // resource release anyway
        case objNameStr =>
          java.lang.management.ManagementFactory.getPlatformMBeanServer().unregisterMBean(
            new javax.management.ObjectName(objNameStr)
          )
      }
    }
  }
}
