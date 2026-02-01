/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package amcas

/**
 * A not-wait-free (but lock-free) variant of the MCAS
 * algorithm described in "A Wait-Free Multi-Word
 * Compare-and-Swap Operation" by Steven Feldman, Pierre
 * LaBorde and Damian Dechev (DOI 10.1007/s10766-014-0308-7).
 *
 * The main difference from the paper is that we omit the
 * wait-free announcement scheme. This makes our algorithm
 * not-wait-free (but it is still lock-free).
 *
 * Methods are identified as implementing "Algorithm N" from
 * the paper (where applicable). Lines are occasionally
 * commented to refer to specific lines of the specific
 * algorithm (e.g., `// X` to refer to line X).
 *
 * A later implementation of essentially the same algorithm
 * (but with full source available) can be found in [Tervel](
 * https://github.com/ucf-cs/Tervel). The relevant paper is
 * "Tervel: A Unification of Descriptor-based Techniques for
 * Non-blocking Programming" by the same authors
 * (DOI: 10.1109/SAMOS.2015.7363668).
 *
 * Naming: the papers about this algorithm either don't
 * name it beyond "MCAS", or call it "WFMCAS", the "WF"
 * meaning "wait-free". As noted above, our variant of the
 * algorithm is *not* wait-free, so calling it WFMCAS would
 * not be appropriate. So we call it AMCAS, where "A" refers
 * to the *association* model it uses.
 */
private[mcas] final class Amcas(
  private[choam] final override val osRng: OsRng,
  private[choam] final override val stripes: Int,
) extends Mcas.UnsealedMcas {

  private[this] val RETURN: AnyRef =
    new AnyRef

  private[this] val FAIL: AnyRef =
    new AnyRef

  final override def currentContext(): Mcas.ThreadContext = {
    sys.error("TODO")
  }

  private[choam] final override def makeCopy(osRng: OsRng): Mcas = {
    sys.error("TODO")
  }

  private[choam] final override def isThreadSafe: Boolean = {
    true
  }

  private[choam] final override def close(): Unit = {
    sys.error("TODO")
  }

  /**
    * Algorithm 1 `invokeMCAS` in the paper
    *
    * @return `true` iff the op completed successfully
    */
  private[amcas] final def invokeMcas(desc: AmcasDescriptor): Boolean = {
    val lastIdx = desc.lastIdx
    var idx = 0
    while ((idx <= lastIdx) && (desc.mchs.get(lastIdx) eq null)) { // not yet done
      placeMcasHelper(desc, idx, firstTime = (idx == 0))
      idx += 1
    }
    val res = desc.mchs.get(lastIdx) ne McasHelper.FAILED
    removeMcasHelper(passed = res, desc = desc)
    res
  }

  /**
   * Algorithm 2 `placeMCasHelper` in the paper
   *
   * TODO: we're using *release* CASes; is this okay?
   */
  private[this] final def placeMcasHelper(desc: AmcasDescriptor, idx: Int, firstTime: Boolean): Unit = {
    val address = desc.address(idx) // 1
    val eValue = desc.expectedValue(idx)
    val mch = new McasHelper(desc, idx)
    if (firstTime) {
      desc.mchs.setPlain(idx, mch)
    }

    @tailrec
    def go(cValue: AnyRef): Unit = {
      if (firstTime || (desc.mchs.get(idx) eq null)) { // 11
        val cValue2: AnyRef = cValue match {
          case other: McasHelper => // 36
            if (other.hasSameCasRow(mch)) { // 40
              // try to associate `other` (which also belongs to us!):
              val wit = desc.mchs.compareAndExchangeRelease(idx, null, other)
              if ((wit ne null) && (wit ne other)) {
                // `other` was mistakenly installed, help fix it:
                address.unsafeCmpxchgR(other, eValue) : Unit // if it fails, someone else already fixed it
              } // else: associating it was successful
              RETURN // we're done (either `other` is fine for us, or our op is done) // 47
            } else if (shouldReplace(eValue, other)) { // 48
              // `other` belongs to another operation; we've helped it, and
              // it's logical value matches eValue, so we can replace `other`:
              val wit = address.unsafeCmpxchgR(other, mch)
              if (wit eq other) {
                if (!firstTime) {
                  // associate it:
                  val mchWit = desc.mchs.compareAndExchangeRelease(idx, null, mch) // 52
                  // NB: in the previous line, the paper assigns the witness to
                  // NB: `cValue`, but in this branch there is an unconditional
                  // NB: return, so that's only used for `rcUnWatch`ing it
                  if ((mchWit ne null) && (mchWit ne mch)) {
                    // we've mistakenly installed `mch`, fix it:
                    address.unsafeCmpxchgR(mch, eValue)
                    ()
                  }
                }
                RETURN // we're done (either associated, or our op is done) // 59
              } else {
                wit // retry // 62
                // NB: There is likely a bug in line 62 of the pseudocode in
                // NB: the paper: it doesn't assign the witness acquired from
                // NB: a failed CAS above to cValue. In all other cases it
                // NB: does though, and logically it should in this case too.
                // NB: (We should retry starting with the latest contents of
                // NB: `address`.) The implementation in Tervel also retries
                // NB: with a fresh value.
              }
            } else {
              // `eValue` is certainly not the same as `other`'s observed logical value:
              FAIL // 64
            }
          case _ => // 22
            val wit = address.unsafeCmpxchgR(eValue, mch)
            if (wit eq eValue) {
              if (!firstTime) {
                // associate it:
                val mchWit = desc.mchs.compareAndExchangeRelease(idx, null, mch)
                if ((mchWit ne null) && (mchWit ne mch)) {
                  // we've mistakenly installed `mch`, fix it:
                  address.unsafeCmpxchgR(mch, eValue) // if it fails, someone else already fixed it
                  ()
                } // else: associating it was successful (either by us or by a helper)
              } // else: already associated (because firstTime)
              RETURN // we're done // 32
            } else {
              FAIL // 34
              // NB: There is likely a bug in line 34 of the pseudocode in
              // NB: the paper: it retries (`continue`) in this case. But
              // NB: as we've observed a regular (non-descriptor) value in
              // NB: `address` being non-equal to `eValue`, we should fail
              // NB: the whole MCAS operation here. The implementation in
              // NB: Tervel also fails the op in this case.
            }
        }
        if (cValue2 eq RETURN) {
          ()
        } else if (cValue2 eq FAIL) { // 65
          val failWit = desc.mchs.compareAndExchangeRelease(idx, null, McasHelper.FAILED)
          if (failWit eq null) {
            desc.mchs.compareAndExchangeRelease(desc.lastIdx, null, McasHelper.FAILED) : Unit
          }
          // 71
        } else {
          go(cValue = cValue2)
        }
      } // else: () // 72
    }

    go(cValue = address.unsafeGetV()) : Unit // 9
  }

  /**
   * Algorithm 3 `shouldReplace` in the paper
   *
   * @param ev The expected value of OUR operation
   * @param mch The `McasHelper` of another operation,
   *            which was observed to be in the address
   *            we want to work on.
   * @return `true` iff the other operation was completed,
   *         and the logical value in the address is the
   *         same as our `ev`.
   */
  private[this] final def shouldReplace(ev: AnyRef, mch: McasHelper): Boolean = {
    val desc = mch.desc
    val idx = mch.idx
    val expectedValue = desc.expectedValue(idx)
    val newValue = desc.newValue(idx)
    if ((expectedValue ne ev) && (newValue ne ev)) { // 2
      // whatever is the actual logical value, it
      // is definitely not the same as `ev`, so our
      // operation must fail:
      false
    } else {
      // potential match, help the other op:
      val res = helpComplete(mch)
      if (res && (desc.mchs.get(idx) eq mch)) { // 6
        // success, and `mch` is associated with its CasRow,
        // so the logical value is `newValue`
        (newValue eq ev)
      } else {
        // otherwise the logical value is `expectedValue`
        (expectedValue eq ev)
      }
    }
  }

  /**
   * Algorithm 5 `helpComplete` in the paper
   *
   * @return `true` iff the operation we're helping completed successfully
   */
  private[this] final def helpComplete(mch: McasHelper): Boolean = {
    // TODO: is circular helping possible?
    // TODO: (in the paper this method checks `recurDepth`)
    val desc = mch.desc
    val lastIdx = desc.lastIdx

    def go(idx: Int): Unit = {
      if ((idx <= lastIdx) && (desc.mchs.get(lastIdx) eq null)) { // 12 and 20
        // the op is not yet done, let's help:
        placeMcasHelper(desc, idx, firstTime = false)
        if (desc.mchs.get(idx) ne McasHelper.FAILED) {
          // success, let's continue:
          go(idx + 1)
        } // else: the op we're helping failed // 15
      }
    }

    go(mch.idx + 1) // 5
    desc.mchs.get(lastIdx) ne McasHelper.FAILED
  }

  /**
   * Algorithm 6 `removeMCasHelper` in the paper
   *
   * @param passed Whether the op was successful
   * @param desc The descriptor of the op
   */
  private[this] final def removeMcasHelper(passed: Boolean, desc: AmcasDescriptor): Unit = {
    val lastIdx = desc.lastIdx
    var idx = 0
    while ((idx <= lastIdx) && (desc.mchs.get(idx) ne McasHelper.FAILED)) { // 11 and 2
      val ov = desc.mchs.get(idx)
      val nv = if (passed) desc.newValue(idx) else desc.expectedValue(idx)
      desc.address(idx).unsafeCmpxchgR(ov, nv) : Unit // 5 and 7 // TODO: is R enough here?
      idx += 1
    }
  }

  /**
   * Algorithm 7 `mcasRead` in the paper
   *
   * @param address The memory location to read from
   * @return The logical value at `address` (after possibly helping an ongoing op)
   */
  private[amcas] final def mcasRead(address: MemoryLocation[AnyRef]): AnyRef = {
    address.unsafeGetV() match {
      case mch: McasHelper => // 29
        val desc = mch.desc
        val idx = mch.idx
        if (desc.mchs.get(idx) eq McasHelper.FAILED) { // 33
          // already failed, so logical value is expectedValue:
          desc.expectedValue(idx)
        } else {
          val res = helpComplete(mch)
          if (res && (desc.mchs.get(idx) eq mch)) { // 37
            // success, and `mch` is associated with its CasRow
            desc.newValue(idx)
          } else {
            // failed
            desc.expectedValue(idx)
          }
        }
      case value => // 18
        value // 28
    }
  }
}
