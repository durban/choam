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

import java.lang.Character.{ isHighSurrogate, isLowSurrogate }
import java.nio.{ ByteBuffer, ByteOrder }

import scala.collection.mutable.ArrayBuffer

// TODO: more tests for reproducibility
// TODO: everything could be optimized to a single `seed.modify { ... }`
// TODO: remove asserts after a while

private object DeterministicRandom {

  def apply(initialSeed: Long): Axn[SplittableRandom[Axn]] = {
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

  private final val Alphanumeric =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

  private final val LenAlphanumeric =
    62

  /**
   * '!'; technically 0x20 (space) is also printable, but
   * `scala.util.Random#nextPrintableChar` and
   * `cats.effect.std.Random#nextPrintableChar` exclude it,
   * so we have to exclude it too.
   */
  private final val MinPrintableIncl =
    0x21 // '!'

  /**
   * '~' + 1
   */
  private final val MaxPrintableExcl =
    0x7f

  private final val MinLowSurrogate =
    0xdc00.toChar

  private final val MinSurrogate =
    0xd800.toChar

  private final val NumChars =
    65536

  private final val NumHighSurrogates =
    1024

  private final val NumLowSurrogates =
    1024

  private final val NumSurrogates =
    NumHighSurrogates + NumLowSurrogates

  private final val NumNonSurrogates =
    NumChars - NumSurrogates
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
  with SplittableRandom[Axn] {

  import DeterministicRandom._

  private[this] val nextSeed: Axn[Long] =
    seed.updateAndGet(_ + gamma)

  private[this] final def mix64(s: Long): Long =
    staffordMix13(s)

  private[this] final def mix32(s: Long): Int =
    (staffordMix04(s) >>> 32).toInt

  private[this] final def mixGamma(s: Long): Long = {
    // The paper uses `staffordMix13`, but the
    // OpenJDK impl uses MurmurHash3's mix; it
    // probably doesn't matter much, so we choose
    // to be compatible with OpenJDK.
    val z: Long = murmurHash3Mix(s) | 1L // <- makes sure it's odd
    val n: Int = java.lang.Long.bitCount(z ^ (z >>> 1))
    // The `mixGamma` pseudocode in the paper
    // "Fast Splittable Pseudorandom Number Generators"
    // contains (1) `if (n >= 24)`, however the text writes
    // (2) "we require that the number of such pairs ...
    // exceed 24; if it does not ..." (then XOR with the
    // constant). The `SplittableRandom` class in OpenJDK
    // uses (3) `if (n < 24)`. That's 3 different versions;
    // we go with (3) here, because (1) doesn't make sense,
    // and there is little difference between (2) and (3),
    // and this way we're compatible with the OpenJDK impl.
    val res: Long = if (n < 24) {
      z ^ 0xaaaaaaaaaaaaaaaaL
    } else {
      z
    }
    assert((res & 1L) == 1L)
    res
  }

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

  private[this] final def murmurHash3Mix(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 33)
    n *= 0xff51afd7ed558ccdL
    n ^= (n >>> 33)
    n *= 0xc4ceb9fe1a85ec53L
    n ^= (n >>> 33)
    n
  }

  final override def split: Axn[SplittableRandom[Axn]] = {
    seed.modifyWith { (sd: Long) =>
      val s1: Long = sd + gamma
      val otherSeed: Long = mix64(s1)
      val s2 = s1 + gamma
      val otherGamma: Long = mixGamma(s2)
      Ref[Long](otherSeed).map { otherSeed =>
        (s2, new DeterministicRandom(otherSeed, otherGamma))
      }
    }
  }

  final override def nextBoolean: Axn[Boolean] =
    nextInt.map { r => r < 0 }

  final override def nextBytes(n: Int): Axn[Array[Byte]] = {
    require(n >= 0)
    seed.modify[Array[Byte]] { (sd: Long) =>
      val arr = new Array[Byte](n)
      val buf = ByteBuffer.wrap(arr)
      buf.order(ByteOrder.LITTLE_ENDIAN)
      var s: Long = sd

      /** Put the last (at most 7) bytes into buf */
      def putLastBytes(nBytes: Int, r: Long): Unit = {
        if (nBytes > 0) {
          buf.put(r.toByte)
          putLastBytes(nBytes - 1, r >>> 8)
        }
      }

      while ({
        val rem = buf.remaining
        if (rem > 0) {
          s += gamma
          if (rem >= 8) {
            buf.putLong(mix64(s))
            true
          } else {
            putLastBytes(nBytes = rem, r = mix64(s))
            false
          }
        } else {
          false
        }
      }) {}
      (s, arr)
    }
  }

  final override def nextDouble: Axn[Double] =
    nextLong.map(doubleFromLong)

  private[this] final def doubleFromLong(n: Long): Double =
    (n >>> 11) * DoubleUlp

  final override def betweenDouble(minInclusive: Double, maxExclusive: Double): Axn[Double] = {
    import java.lang.Double.{ doubleToLongBits, longBitsToDouble }
    require(minInclusive < maxExclusive)
    nextDouble.map { (d: Double) =>
      val r: Double = (d * (maxExclusive - minInclusive)) + minInclusive
      if (r >= maxExclusive) { // this can happen due to rounding
        val correction = if (maxExclusive < 0.0) 1L else -1L
        longBitsToDouble(doubleToLongBits(maxExclusive) + correction)
      } else {
        r
      }
    }
  }

  final override def nextFloat: Axn[Float] =
    nextInt.map { n => (n >>> 8) * FloatUlp }

  final override def betweenFloat(minInclusive: Float, maxExclusive: Float): Axn[Float] = {
    import java.lang.Float.{ floatToIntBits, intBitsToFloat }
    require(minInclusive < maxExclusive)
    nextFloat.map { (f: Float) =>
      val r: Float = (f * (maxExclusive - minInclusive)) + minInclusive
      if (r >= maxExclusive) { // this can happen due to rounding
        val correction = if (maxExclusive < 0.0f) 1 else -1
        intBitsToFloat(floatToIntBits(maxExclusive) + correction)
      } else {
        r
      }
    }
  }

  /** Box-Muller transform / Marsaglia polar method */
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
      // TODO: Because of this, nextGaussian doesn't
      // TODO: generate the same values as SplittableRandom!
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

  final override def betweenInt(minInclusive: Int, maxExclusive: Int): Axn[Int] = {
    require(minInclusive < maxExclusive)
    val n: Int = maxExclusive - minInclusive
    val m: Int = n - 1
    seed.modify[Int] { (sd: Long) =>
      if ((n & m) == 0) { // power of 2
        val s: Long = sd + gamma
        (s, (mix32(s) & m) + minInclusive)
      } else if (n > 0) {
        var s: Long = sd
        var r: Int = -1 // unused value
        while ({
          s += gamma
          val u: Int = mix32(s) >>> 1
          r = u % n
          (u + m - r) < 0
        }) {}
        (s, r + minInclusive)
      } else { // range not representable as Int
        var s: Long = sd + gamma
        var r: Int = mix32(s)
        while ((r < minInclusive) || (r >= maxExclusive)) {
          s += gamma
          r = mix32(s)
        }
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

  final override def betweenLong(minInclusive: Long, maxExclusive: Long): Axn[Long] = {
    require(minInclusive < maxExclusive)
    val n: Long = maxExclusive - minInclusive
    val m: Long = n - 1
    seed.modify[Long] { (sd: Long) =>
      if ((n & m) == 0L) { // power of 2
        val s: Long = sd + gamma
        (s, (mix64(s) & m) + minInclusive)
      } else if (n > 0L) {
        var s: Long = sd
        var r: Long = -1L // unused value
        while ({
          s += gamma
          val u: Long = mix64(s) >>> 1
          r = u % n
          (u + m - r) < 0L
        }) {}
        (s, r + minInclusive)
      } else { // range not representable as Long
        var s: Long = sd + gamma
        var r: Long = mix64(s)
        while ((r < minInclusive) || (r >= maxExclusive)) {
          s += gamma
          r = mix64(s)
        }
        (s, r)
      }
    }
  }

  final override def shuffleList[A](l: List[A]): Axn[List[A]] = {
    if (l.length > 1) {
      seed.modify[List[A]] { (sd: Long) =>
        val arr = ArrayBuffer.from(l)
        val newSeed = shuffleArray(arr, sd)
        (newSeed, arr.toList)
      }
    } else {
      Rxn.pure(l)
    }
  }

  final override def shuffleVector[A](v: Vector[A]): Axn[Vector[A]] = {
    if (v.length > 1) {
      seed.modify[Vector[A]] { (sd: Long) =>
        val arr = ArrayBuffer.from(v)
        val newSeed = shuffleArray(arr, sd)
        (newSeed, arr.toVector)
      }
    } else {
      Rxn.pure(v)
    }
  }

  /** Fisher-Yates / Knuth shuffle */
  private[this] final def shuffleArray[A](
    arr: ArrayBuffer[A],
    sd: Long,
  ): Long = {
    def swap(j: Int, i: Int): Unit = {
      val tmp = arr(j)
      arr(j) = arr(i)
      arr(i) = tmp
    }
    var s: Long = sd
    var i: Int = arr.length - 1
    while (i > 0) {
      val bound = i + 1
      val m: Int = bound - 1
      var j: Int = -1 // unused value
      if ((bound & m) == 0L) { // power of 2
        s += gamma
        j = mix32(s) & m
      } else {
        while (j == -1) {
          s += gamma
          j = tryNextIntBoundedNotPowerOf2(bound = bound, seed = s)
        }
      }
      swap(j, i)
      i -= 1
    }
    s
  }

  private[this] final def tryNextIntBoundedNotPowerOf2(bound: Int, seed: Long): Int = {
    val m: Int = bound - 1
    val u: Int = mix32(seed) >>> 1
    val r: Int = u % bound
    if ((u + m - r) < 0) -1 // retry
    else r
  }

  final override def nextAlphaNumeric: Axn[Char] = {
    nextIntBounded(LenAlphanumeric).map { (i: Int) =>
      Alphanumeric.charAt(i)
    }
  }

  final override def nextPrintableChar: Axn[Char] = {
    betweenInt(MinPrintableIncl, MaxPrintableExcl).map { (i: Int) =>
      i.toChar
    }
  }

  /**
   * We also generate surrogates, unless they
   * would be illegal at that position.
   */
  final override def nextString(length: Int): Axn[String] = {
    require(length >= 0)
    if (length == 0) {
      Rxn.pure("")
    } else {
      seed.modify[String] { (sd: Long) =>
        val arr = new Array[Char](length)
        var s: Long = sd
        var idx: Int = 0
        while (idx < length) {
          s += gamma
          if ((idx + 1) == length) {
            // last char, can't generate surrogates:
            arr(idx) = nextNonSurrogate(s)
          } else {
            // inside char, but can't generate a
            // low surrogate, because a surrogate
            // pair starts with a high surrogate:
            val r = nextNormalOrHighSurrogate(s)
            arr(idx) = r
            if (isHighSurrogate(r)) {
              // we also generate its pair:
              s += gamma
              idx += 1
              arr(idx) = nextLowSurrogate(s)
            }
          }
          idx += 1
        }
        (s, new String(arr))
      }
    }
  }

  private final def nextNormalOrHighSurrogate(seed: Long): Char = {
    val m: Int = (NumChars - NumLowSurrogates) - 1
    var r: Int = mix32(seed) & m
    if (r >= MinLowSurrogate) {
      r += NumLowSurrogates
    }
    val c: Char = r.toChar
    assert(c.toInt == r)
    assert(!isLowSurrogate(c))
    c
  }

  private[this] final def nextLowSurrogate(seed: Long): Char = {
    val m = NumLowSurrogates - 1
    val r = ((mix32(seed) & m) + MinLowSurrogate)
    val c = r.toChar
    assert(c.toInt == r)
    assert(isLowSurrogate(c))
    c
  }

  private[this] final def nextNonSurrogate(seed: Long): Char = {
    val m = NumNonSurrogates - 1
    var r = mix32(seed) & m
    if (r >= MinSurrogate) {
      r += NumSurrogates
    }
    val c = r.toChar
    assert(c.toInt == r)
    assert(!isHighSurrogate(c))
    assert(!isLowSurrogate(c))
    c
  }
}
