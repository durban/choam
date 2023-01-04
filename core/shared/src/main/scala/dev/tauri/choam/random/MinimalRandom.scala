/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package random

import cats.effect.std.Random

private object MinimalRandom {
  def unsafe(initialSeed: Long): Random[Axn] = {
    new MinimalRandom(Ref.unsafe(initialSeed), RandomBase.GoldenGamma)
  }
}

/**
 * Uses `RandomBase` for everything, implements
 * only the absolutely necessary methods. (For
 * testing and benchmarking.)
 */
private final class MinimalRandom private (
  seed: Ref[Long],
  gamma: Long,
) extends RandomBase {

  private[this] val nextSeed: Axn[Long] =
    seed.updateAndGet(_ + gamma)

  private[this] final def mix64(s: Long): Long =
    staffordMix13(s)

  private[this] final def mix32(s: Long): Int =
    (staffordMix04(s) >>> 32).toInt

  private[this] final def staffordMix13(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 30)
    n *= 0xbf58476d1ce4e5b9L
    n ^= (n >>> 27)
    n *= 0x94d049bb133111ebL
    n ^= (n >>> 31)
    n
  }

  private[this] final def staffordMix04(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 33)
    n *= 0x62a9d9ed799705f5L
    n ^= (n >>> 28)
    n *= 0xcb24d0a5c88c35b3L
    n ^= (n >>> 32)
    n
  }

  final def nextInt: Axn[Int] =
    nextSeed.map(mix32)

  final def nextLong: Axn[Long] =
    nextSeed.map(mix64)
}
