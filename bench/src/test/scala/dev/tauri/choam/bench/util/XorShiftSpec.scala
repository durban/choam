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
package bench
package util

class XorShiftSpec extends BaseSpec {

  "XorShift" should "mostly work" in {
    val xs = XorShift()
    val N = 1000000

    // check Ints:
    val ns = Vector.fill(N) { xs.nextInt() }
    ns.toSet.size.toDouble should be >= (0.9 * N)
    val negs = ns.filter(n => n < 0).size.toDouble
    negs should be >= (0.4 * N)
    negs should be <= (0.6 * N)

    // check Longs:
    val ms = Vector.fill(N) { xs.nextLong() }
    ms.toSet.size.toDouble should be >= (0.9 * N)
    val negls = ms.filter(m => m < 0).size.toDouble
    negls should be >= (0.4 * N)
    negls should be <= (0.6 * N)
  }
}
