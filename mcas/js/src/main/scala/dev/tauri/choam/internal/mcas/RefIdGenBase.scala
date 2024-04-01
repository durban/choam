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

import java.util.concurrent.atomic.AtomicLong

abstract class RefIdGenBase {

  private[this] val ctr =
    new AtomicLong(Long.MinValue)

  protected final def getAndAddCtrO(x: Long): Long = {
    this.ctr.getAndAdd(x)
  }
}

object RefIdGenBase {

  @inline final val GAMMA =
    0x9e3779b97f4a7c15L;

  /**
   * Next power of 2 which is `>= x`.
   *
   * `clp2` from Hacker's Delight by Henry S. Warren, Jr. (section 3–2).
   */
  @inline final def nextPowerOf2(x: Int): Int = {
    assert((x > 0) && (x <= (1 << 30)))
    0x80000000 >>> (Integer.numberOfLeadingZeros(x - 1) - 1)
  }
}
