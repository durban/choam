/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.std.Random

// TODO: finish implementing the derived methods
// TODO: write tests for reproducibility
// TODO: everything could be optimized to a single `seed.modify { ... }`

private object DeterministicRandom {

  def apply(initialSeed: Long): Axn[Random[Axn]] = {
    Ref(initialSeed).map { (seed: Ref[Long]) =>
      new DeterministicRandom(seed, GoldenGamma)
    }
  }

  private final val GoldenGamma =
    0x9e3779b97f4a7c15L

  private final val DoubleUlp =
    1.0d / (1L << 53)

  private final val FloatUlp =
    1.0f / (1L << 24)
}

/**
 * Based on "Fast Splittable Pseudorandom Number Generators"
 * by Guy L. Steele Jr., Doug Lea, and Christine H. Flood
 * (http://gee.cs.oswego.edu/dl/papers/oopsla14.pdf).
 *
 * Some of the derived methods were adapted from the algorithms
 * in the public domain JSR-166 ThreadLocalRandom
 * (http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
 *
 * The 2 finalizers (`staffordMix13` and `staffordMix04`) are from
 * https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html.
 *
 * The mutable state of the RNG is held by a `Ref[Long]`,
 * so rollbacks also affect the state. Thus, the RNG can
 * be used if reproducible random numbers are needed.
 * (However, nondeterministic multi-threaded access can of
 * course cause non-reproducibility.)
 */
private final class DeterministicRandom(
  seed: Ref[Long],
  gamma: Long,
) extends DeterministicRandomPlatform
  with Random[Axn] {

  import DeterministicRandom._

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

  final override def betweenDouble(minInclusive: Double, maxExclusive: Double): Axn[Double] =
    sys.error("TODO")

  final override def betweenFloat(minInclusive: Float, maxExclusive: Float): Axn[Float] =
    sys.error("TODO")

  final override def betweenInt(minInclusive: Int, maxExclusive: Int): Axn[Int] =
    sys.error("TODO")

  final override def betweenLong(minInclusive: Long, maxExclusive: Long): Axn[Long] =
    sys.error("TODO")

  final override def nextAlphaNumeric: Axn[Char] =
    sys.error("TODO")

  final override def nextBoolean: Axn[Boolean] =
    nextInt.map { r => r < 0 }

  final override def nextBytes(n: Int): Axn[Array[Byte]] = {
    require(n >= 0)
    sys.error("TODO")
  }

  final override def nextDouble: Axn[Double] =
    nextLong.map(doubleFromLong)

  private[this] final def doubleFromLong(n: Long): Double =
    (n >>> 11) * DoubleUlp

  final override def nextFloat: Axn[Float] =
    nextInt.map { n => (n >>> 8) * FloatUlp }

  /** Box–Muller transform / Marsaglia polar method */
  final override def nextGaussian: Axn[Double] = {
    seed.modify[Double] { (sd: Long) =>
      var n: Long = sd
      var v1: Double = Double.NaN // unused value
      var s: Double = Double.NaN // unused value
      while ({
        n += gamma
        val d1: Double = doubleFromLong(mix64(n))
        n += gamma
        val d2: Double = doubleFromLong(mix64(n))
        v1 = (2 * d1) - 1
        val v2: Double = (2 * d2) - 1
        s = (v1 * v1) + (v2 * v2)
        (s >= 1) || (s == 0)
      }) {}

      val multiplier: Double =
        strictMathSqrt(-2 * strictMathLog(s) / s)
      (n, v1 * multiplier)
      // NB: we actually generated 2 random Doubles,
      // (the other one is `v2 * multiplier`), but
      // we don't bother saving the other one for
      // next time (it probably doesn't worth it).
    }
  }

  final override def nextInt: Axn[Int] =
    nextSeed.map(mix32)

  final override def nextIntBounded(bound: Int): Axn[Int] = {
    require(bound > 0)
    val m: Int = bound - 1
    if ((bound & m) == 0) { // power of 2
      nextInt.map { r => r & m }
    } else {
      seed.modify[Int] { (sd: Long) =>
        var s: Long = sd
        var r: Int = -1 // unused value
        while ({
          s += gamma
          val u: Int = mix32(s) >>> 1
          r = u % bound
          (u + m - r) < 0
        }) {}

        (s, r)
      }
    }
  }

  final override def nextLong: Axn[Long] =
    nextSeed.map(mix64)

  final override def nextLongBounded(bound: Long): Axn[Long] = {
    require(bound > 0L)
    val m: Long = bound - 1
    if ((bound & m) == 0L) { // power of 2
      nextLong.map { r => r & m }
    } else {
      seed.modify[Long] { (sd: Long) =>
        var s: Long = sd
        var r: Long = -1L // unused value
        while ({
          s += gamma
          val u: Long = mix64(s) >>> 1
          r = u % bound
          (u + m - r) < 0L
        }) {}

        (s, r)
      }
    }
  }

  final override def nextPrintableChar: Axn[Char] =
    sys.error("TODO")

  final override def nextString(length: Int): Axn[String] =
    sys.error("TODO")

  final override def shuffleList[A](l: List[A]): Axn[List[A]] =
    sys.error("TODO")

  final override def shuffleVector[A](v: Vector[A]): Axn[Vector[A]] =
    sys.error("TODO")
}
