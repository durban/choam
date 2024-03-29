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

import java.lang.ref.{ Reference, WeakReference }

private[mcas] object Emcas {
  /** For testing */
  val inst: Emcas =
    Mcas.internalEmcas
}

/**
 * Efficient Multi-word Compare and Swap (EMCAS):
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private[mcas] final class Emcas extends GlobalContext { global =>

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
   * TODO: Do we still need markers? The version numbers "should"
   * TODO: protect us from ABA-problems, and we're currently
   * TODO: always using fresh descriptors.
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
   * freely replaced (with a CAS) by a descriptor during an operation.
   * (But the new descriptor must have a mark.) The content can have the
   * following  possible states:
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
   * (https://web.archive.org/web/20220215230304/https://www.researchgate.net/profile/Aleksandar-Dragojevic/publication/37470225_Stretching_Transactional_Memory/links/0912f50d430e2cf991000000/Stretching-Transactional-Memory.pdf),
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
   * `unsafeGetVersionVolatile` and `unsafeCmpxchgVersionVolatile`
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
   * @param replace: Pass `false` to not do any replacing/clearing.
   */
  private[this] final def readValue[A](ref: MemoryLocation[A], ctx: EmcasThreadContext, replace: Boolean): HalfWordDescriptor[A] = {
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
              val parent = wd.parent
              val parentStatus = parent.getStatus()
              if (parentStatus == McasStatus.Active) {
                // active op without a mark: this can
                // happen if a thread died during an op;
                // we help the active op, then retry ours:
                MCAS(parent, ctx = ctx)
                go(mark = null, ver1 = ver1)
              } else { // finalized op
                val successful = (parentStatus != McasStatus.FailedVal)
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
                HalfWordDescriptor(ref, ov = a, nv = a, version = currVer)
              }
            }
          } else { // mark ne null
            // OK, we're already holding the descriptor
            val parent = wd.parent
            val parentStatus = parent.getStatus()
            if (parentStatus == McasStatus.Active) {
              MCAS(parent, ctx = ctx) // help the other op
              go(mark = mark, ver1 = ver1) // retry
            } else { // finalized
              val successful = (parentStatus != McasStatus.FailedVal)
              val a = if (successful) wd.cast[A].nv else wd.cast[A].ov
              val currVer = if (successful) parentStatus else wd.oldVersion
              Reference.reachabilityFence(mark)
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
    replace: Boolean,
    currentVersion: Long,
  ): Unit = {
    if (replace) {
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
      val wit = ref.unsafeCmpxchgVersionVolatile(currentInRef, currentVersion)
      if (wit == currentInRef) {
        // We've successfully updated the version.
        // Now we'll replace the descriptor with the final value.
        // If this CAS fails, someone else might've
        // replaced the desc with the final value, or
        // maybe started another operation; in either case,
        // there is nothing to do here.
        ref.unsafeCasVolatile(ov.castToData, nv) // TODO: could be Release
        // Possibly also clean up the weakref:
        if (weakref ne null) {
          assert(weakref.get() eq null)
          // We also delete the (now empty) `WeakReference`
          // object, to help the GC. If this CAS fails,
          // that means a new op already installed a new
          // weakref; nothing to do here.
          ref.unsafeCasMarkerVolatile(weakref, null) // TODO: could be Release
          ()
        }
      } else {
        assert(wit >= currentVersion)
        // concurrent write, no need to replace the
        // descriptor (see the comment below)
      }
    } // else:
    // either a concurrent write to a newer version, in which
    // case there is no need to replace the descriptor, as
    // the newer operation will install a newer descriptor;
    // or it is already correct, in which case there is a
    // concurrent `replaceDescriptor` going on, so we let
    // that one win and replace the descriptor
  }

  // TODO: this could have an optimized version, without creating a hwd
  private[mcas] final def readDirect[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): A = {
    val hwd = readIntoHwd(ref, ctx)
    hwd.nv
  }

  private[mcas] final def readIntoHwd[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): HalfWordDescriptor[A] = {
    readValue(ref, ctx, replace = true)
  }

  @tailrec
  private[mcas] final def readVersion[A](ref: MemoryLocation[A], ctx: EmcasThreadContext): Long = {
    val ver1 = ref.unsafeGetVersionVolatile()
    ref.unsafeGetVolatile() match {
      case wd: WordDescriptor[_] =>
        // TODO: we may need to hold the marker here!
        val parent = wd.parent
        val s = parent.getStatus()
        if (s == McasStatus.Active) {
          // help and retry:
          MCAS(parent, ctx = ctx)
          readVersion(ref, ctx)
        } else if (s == McasStatus.FailedVal) {
          wd.oldVersion
        } else { // successful
          s
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
  private[emcas] final def MCAS(desc: EmcasDescriptor, ctx: EmcasThreadContext): Long = {

    @tailrec
    def tryWord[A](wordDesc: WordDescriptor[A]): Long = {
      var content: A = nullOf[A]
      var value: A = nullOf[A]
      var weakref: WeakReference[AnyRef] = null
      var mark: AnyRef = null
      val address = wordDesc.address
      var version: Long = address.unsafeGetVersionVolatile()
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
        content = address.unsafeGetVolatile()
        content match {
          case wd: WordDescriptor[_] =>
            if (mark eq null) {
              // not holding it yet
              weakref = address.unsafeGetMarkerVolatile()
              mark = if (weakref ne null) weakref.get() else null
              if (mark ne null) {
                // continue with another iteration, and re-read the
                // descriptor, while holding the mark
              } else { // mark eq null
                // the old descriptor is unused, could be detached
                val parent = wd.parent
                val parentStatus = parent.getStatus()
                if (parentStatus == McasStatus.Active) {
                  // active op without a mark: this can
                  // happen if a thread died during an op
                  if (wd eq wordDesc) {
                    // this is us!
                    // already points to the right place, early return:
                    return McasStatus.Successful // scalafix:ok
                  } else {
                    // we help the active op (who is not us),
                    // then continue with another iteration:
                    MCAS(parent, ctx = ctx)
                    ()
                  }
                } else { // finalized op
                  if (parentStatus == McasStatus.FailedVal) {
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
                // already points to the right place, early return:
                return McasStatus.Successful // scalafix:ok
              } else {
                // At this point, we're sure that `wd` belongs to another op
                // (not `desc`), because otherwise it would've been equal to
                // `wordDesc` (we're assuming that any WordDescriptor only
                // appears at most once in an MCASDescriptor).
                val parent = wd.parent
                val parentStatus = parent.getStatus()
                if (parentStatus == McasStatus.Active) {
                  MCAS(parent, ctx = ctx) // help the other op
                  // Note: we're not "helping" ourselves for sure, see the comment above.
                  // Here, we still don't have the value, so the loop must retry.
                  ()
                } else if (parentStatus == McasStatus.FailedVal) {
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
            val version2 = address.unsafeGetVersionVolatile()
            if (version == version2) {
              // ok, we have a version that belongs to `value`
              go = false
              weakref = address.unsafeGetMarkerVolatile()
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
        Reference.reachabilityFence(mark)
        // Expected value is different:
        McasStatus.FailedVal
      } else if (version != wordDesc.oldVersion) {
        Reference.reachabilityFence(mark)
        // The expected value is the same,
        // but the expected version isn't:
        McasStatus.FailedVal
      } else if (desc.getStatus() != McasStatus.Active) {
        Reference.reachabilityFence(mark)
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
          address.unsafeCasMarkerVolatile(weakref, weakref2)
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
        if (weakRefOk && address.unsafeCasVolatile(content, wordDesc.castToData)) {
          Reference.reachabilityFence(mark)
          McasStatus.Successful
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
      if (words ne null) {
        if (words.hasNext) {
          val word = words.next()
          if (word ne null) {
            val twr = tryWord(word)
            assert((twr == McasStatus.Successful) || (twr == McasStatus.FailedVal) || (twr == EmcasStatus.Break))
            if (twr == McasStatus.Successful) go(words)
            else twr
          } else {
            // Another thread already finalized the descriptor,
            // and cleaned up this word descriptor (hence the `null`);
            // thus, we should not continue:
            EmcasStatus.Break
          }
        } else {
          McasStatus.Successful
        }
      } else {
        // Already finalized descriptor, see above
        EmcasStatus.Break
      }
    }

    val r = go(desc.wordIterator())
    assert((r == McasStatus.Successful) || (r == McasStatus.FailedVal) || (r == EmcasStatus.Break))
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
      val wit = desc.cmpxchgStatus(McasStatus.Active, McasStatus.FailedVal)
      assert(wit != McasStatus.Active)
      wit
    } else {
      val realRes = if (r == McasStatus.Successful) {
        // successfully installed all descriptors (~ACQUIRE)
        // but we'll need a new commit-ts, which we will
        // CAS into the descriptor:
        retrieveFreshTs()
      } else {
        r
      }
      val witness: Long = desc.cmpxchgStatus(McasStatus.Active, realRes)
      if (witness == McasStatus.Active) {
        // we finalized the descriptor
        desc.wasFinalized()
        realRes
      } else {
        // someone else already finalized the descriptor, we return its status:
        witness
      }
    }
  }

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
   *
   * TODO: Currently even read-only word descriptors
   * TODO: are installed into the refs (so we can only
   * TODO: have write-write conflicts). If this
   * TODO: changes, they will need special handling
   * TODO: for sharing version numbers. Probably
   * TODO: an additional validation; see section 3.1
   * TODO: in the "Commit phase" paper, about read-write
   * TODO: conflicts. Probably V1 or V4 would be
   * TODO: applicable here; to detect read-write
   * TODO: conflicts, we would do a revalidation of
   * TODO: (probably) only the read-only word
   * TODO: descriptors after ACQUIRE.
   * TODO: However: doing it this way, we'd lose
   * TODO: lock-freedom. If we have 2 EMCAS like
   * TODO: this: [(r1, "a", "b"), (r2, "x", "x")]
   * TODO: and [(r1, "a", "a"), (r2, "x", "y")],
   * TODO: then both can ACQUIRE, and then when
   * TODO: revalidating, both would try to help
   * TODO: recursively themselves. That's an
   * TODO: infinite loop (or probably a stack
   * TODO: overflow). If we can detect this, then
   * TODO: we could just retry without the read-only
   * TODO: optimization (i.e., acquiring even read
   * TODO: only words). But detecting it would probably
   * TODO: need a full-blown cycle detection...
   */
  private[this] final def retrieveFreshTs(): Long = {
    val ts1 = global.getCommitTs()
    val ts2 = global.getCommitTs()
    if (ts1 != ts2) {
      // we've observed someone else changing the version:
      assert(ts2 > ts1)
      ts2
    } else {
      // we try to increment it:
      val candidate = ts1 + Version.Incr
      assert(Version.isValid(candidate)) // detect version overflow
      val ctsWitness = global.cmpxchgCommitTs(ts1, candidate) // TODO: could this be `getAndAdd`? is it faster?
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

  final override def currentContext(): Mcas.ThreadContext =
    global.currentContextInternal()

  private[choam] final override def isThreadSafe =
    true

  private[mcas] final def tryPerformInternal(desc: Descriptor, ctx: EmcasThreadContext): Long = {
    tryPerformDebug(desc = desc, ctx = ctx)
  }

  private[mcas] final def tryPerformDebug(desc: Descriptor, ctx: EmcasThreadContext): Long = {
    if (desc.nonEmpty) {
      val fullDesc = new EmcasDescriptor(desc)
      val res = MCAS(desc = fullDesc, ctx = ctx)
      if (EmcasStatus.isSuccessful(res)) {
        // `Emcas` stores a version in the descriptor,
        // to signify success; however, here we return
        // a constant, to follow the `Mcas` API:
        McasStatus.Successful
      } else {
        assert(res == McasStatus.FailedVal)
        McasStatus.FailedVal
      }
    } else {
      McasStatus.Successful
    }
  }

  /** For testing */
  @throws[InterruptedException]
  private[emcas] final def spinUntilCleanup[A](ref: MemoryLocation[A], max: Long = Long.MaxValue): A = {
    val ctx = this.currentContextInternal()
    var ctr: Long = 0L
    while (ctr < max) {
      ref.unsafeGetVolatile() match {
        case wd: WordDescriptor[_] =>
          if (wd.parent.getStatus() == McasStatus.Active) {
            // CAS in progress, retry
          } else {
            // CAS finalized, but no cleanup yet, read and retry
            readDirect(ref, ctx = ctx)
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
            System.gc()
          } else {
            Thread.sleep(32L)
          }
        }
      }
    }
    nullOf[A]
  }

  // JMX MBean for stats:
  if (GlobalContextBase.enableStatsMbean) {
    val oName = new javax.management.ObjectName(
      f"${GlobalContextBase.emcasJmxStatsNamePrefix}%s-${System.identityHashCode(this)}%08x"
    )
    java.lang.management.ManagementFactory.getPlatformMBeanServer().registerMBean(
      new EmcasJmxStats(this),
      oName,
    )
    // TODO: we never unregister this...
  }
}
