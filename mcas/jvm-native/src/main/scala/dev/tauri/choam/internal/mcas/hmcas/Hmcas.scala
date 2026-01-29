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
package hmcas

/**
 * A not-wait-free (but lock-free) variant of the MCAS
 * algorithm described in "A Wait-Free Multi-Word
 * Compare-and-Swap Operation" by Steven Feldman, Pierre
 * LaBorde and Damian Dechev (DOI 10.1007/s10766-014-0308-7).
 *
 * The main difference from the paper is that we omit the
 * wait-free helping scheme. This makes our algorithm
 * not-wait-free (but it is still lock-free).
 *
 * Methods are identified as implementing "Algorithm N" from
 * the paper (where applicable). Lines are occasionally
 * commented to refer to specific lines of the specific
 * algorithm (e.g., `// X` to refer to line X).
 */
private[mcas] final class Hmcas(
  private[choam] final override val osRng: OsRng,
  private[choam] final override val stripes: Int,
) extends Mcas.UnsealedMcas {

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
   * Algorithm 2 `placeMCasHelper` in the paper
   */
  private[hmcas] final def placeMcasHelper(desc: HmcasDescriptor, idx: Int, firstTime: Boolean): Unit = {
    val address = desc.addresses(idx).cast[AnyRef] // 1
    val eValue = desc.ovs(idx)
    val mch = new McasHelper(desc, idx)
    if (firstTime) {
      desc.mchs.setPlain(idx, mch)
    }

    @tailrec
    def go(cValue: AnyRef): Unit = {
      if (firstTime || (desc.mchs.get(idx) eq null)) { // 11
        cValue match {
          case other: McasHelper => // 36
            if (other.hasSameCasRow(mch)) {
              // try to associate the other:
              val wit = desc.mchs.compareAndExchangeRelease(idx, null, other)
              if ((wit ne null) && (wit ne other)) {
                // help undo:
                address.unsafeCmpxchgR(other, eValue) // if it fails, someone else already fixed it
                ()
              }
            }
          case _ => // 22
            val wit = address.unsafeCmpxchgR(eValue, mch)
            if (wit eq eValue) {
              if (!firstTime) {
                // associate it:
                val mchWit = desc.mchs.compareAndExchangeRelease(idx, null, mch)
                if ((mchWit ne null) && (mchWit ne mch)) {
                  // we've mistakenly installed `mch`, remove it:
                  address.unsafeCmpxchgR(mch, eValue) // if it fails, someone else already fixed it
                  ()
                } // else: associate successful (either by us or a helper)
              }
              // we're done
            } else {
              // retry:
              go(cValue = wit)
            }
        }
      }
    }

    go(cValue = address.unsafeGetV()) // 9
  }
}
