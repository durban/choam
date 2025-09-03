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
package random

import cats.effect.std.Random

import core.{ Rxn, Ref }
import mcas.RefIdGen

/**
 * Uses `RandomBase` for everything, implements
 * only the absolutely necessary methods. (For
 * testing and benchmarking.)
 */
private object MinimalRandom {

  def unsafe1(initialSeed: Long, rig: RefIdGen): Random[Rxn] = {
    new MinimalRandom1(Ref.unsafe(initialSeed, Ref.AllocationStrategy.Padded, rig), RandomBase.GoldenGamma)
  }

  def unsafe2(initialSeed: Long, rig: RefIdGen): Random[Rxn] = {
    new MinimalRandom2(Ref.unsafe(initialSeed, Ref.AllocationStrategy.Padded, rig), RandomBase.GoldenGamma)
  }
}

private abstract class MinimalRandom protected (
  seed: Ref[Long],
  gamma: Long,
) extends RandomBase {

  protected[this] val nextSeed: Rxn[Long] =
    seed.updateAndGet(_ + gamma)

  private[this] final def mix64(s: Long): Long =
    staffordMix13(s)

  private[this] final def mix32(s: Long): Int =
    (staffordMix04(s) >>> 32).toInt

  protected[this] final def nextLongInternal: Rxn[Long] =
    nextSeed.map(mix64)

  final override def nextInt: Rxn[Int] =
    nextSeed.map(mix32)
}

/** Implements only `nextLong` and `nextInt` */
private final class MinimalRandom1(
  seed: Ref[Long],
  gamma: Long,
) extends MinimalRandom(seed, gamma) {

  final override def nextLong: Rxn[Long] =
    nextLongInternal
}

/** Implements only `nextBytes` and `nextInt` */
private final class MinimalRandom2(
  seed: Ref[Long],
  gamma: Long,
) extends MinimalRandom(seed, gamma) {

  final override def nextBytes(n: Int): Rxn[Array[Byte]] =
    nextBytesInternal(n, nextLongInternal)
}
