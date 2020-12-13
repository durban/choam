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

final class XorShift private (private[this] var state: Int) {

  def nextInt(): Int = {
    state ^= (state << 6)
    state ^= (state >>> 21)
    state ^= (state << 7)
    state
  }

  def nextLong(): Long = {
    val m = nextInt().toLong
    val n = nextInt().toLong
    m + (n << 32)
  }
}

object XorShift {

  def apply(): XorShift =
    apply(java.util.concurrent.ThreadLocalRandom.current().nextInt())

  def apply(seed: Int): XorShift =
    new XorShift(seed)
}
