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

private[mcas] abstract class RefIdGenBase extends PaddedMemoryLocationPadding {

  private[mcas] final def getAndAddCtrO(x: Long): Long = ???
}

private[mcas] object RefIdGenBase {

  /**
   * Constant for Fibonacci hashing
   *
   * See Knuth, Donald E. "The Art of Computer Programming"
   * vol. 3 "Sorting and Searching", section 6.4 "Hashing"
   * (page 518 in the 2nd edition)
   *
   * See also https://en.wikipedia.org/wiki/Hash_function#Fibonacci_hashing
   */
  final val GAMMA = 0x9e3779b97f4a7c15L
}
