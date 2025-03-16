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
package random

import java.lang.Character.{ isHighSurrogate, isLowSurrogate }

import scala.collection.mutable.ArrayBuffer

import cats.effect.std.Random

/**
 * Common implementations of derived RNG methods.
 *
 * Some of these derived methods were adapted from the algorithms
 * in the public domain JSR-166 ThreadLocalRandom
 * (https://web.archive.org/web/20220129102848/http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
 *
 * Note: some of the methods are implemented in terms of
 * each other, so subclasses *must* override either
 * `nextBytes` OR (`nextLong` AND `nextInt`).
 */
private abstract class RandomBase
  extends RandomBasePlatform
  with Random[Axn] {

  import RandomBase._

  def nextLong: Axn[Long] = {
    this.nextBytes(8).map { (arr: Array[Byte]) =>
      this.getLongAt0P(arr)
    }
  }

  def nextInt: Axn[Int] = {
    this.nextLong.map { (r: Long) =>
      r.toInt
    }
  }

  // TODO: there should be a (protected) allocation-less
  // TODO: version of `nextBytes` (for performance)

  def nextBytes(n: Int): Axn[Array[Byte]] =
    nextBytesInternal(n, this.nextLong)

  protected[this] def nextBytesInternal(n: Int, nextLong: Axn[Long]): Axn[Array[Byte]] = {
    require(n >= 0)

    /* Puts the last (at most 7) bytes into buf */
    @tailrec
    def putLastBytes(arr: Array[Byte], nBytes: Int, idx: Int, r: Long): Unit = {
      if (nBytes > 0) {
        arr(idx) = r.toByte
        putLastBytes(arr, nBytes - 1, idx = idx + 1, r = r >>> 8)
      }
    }

    def go(arr: Array[Byte], idx: Int): Axn[Unit] = {
      if (idx < n) {
        val remaining = n - idx
        nextLong.flatMapF { (r: Long) =>
          if (remaining >= 8) {
            Axn.unsafe.delay(this.putLongAtIdxP(arr, idx, r)) *> go(arr, idx + 8)
          } else {
            Axn.unsafe.delay(putLastBytes(arr, nBytes = remaining, idx = idx, r = r))
          }
        }
      } else {
        Rxn.unit
      }
    }

    Axn.unsafe.delay {
      val arr = new Array[Byte](n)
      go(arr, 0).as(arr)
    }.flatten
  }

  def nextLongBounded(bound: Long): Axn[Long] = {
    // TODO: take a look at https://arxiv.org/abs/1805.10941
    require(bound > 0L)
    val m: Long = bound - 1L
    if ((bound & m) == 0L) { // power of 2
      this.nextLong.map { (r: Long) => r & m }
    } else {
      def go: Axn[Long] = this.nextLong.flatMapF { (next: Long) =>
        val u: Long = next >>> 1
        val r: Long = u % bound
        if ((u + m - r) < 0L) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def betweenLong(minInclusive: Long, maxExclusive: Long): Axn[Long] = {
    require(minInclusive < maxExclusive)
    val n: Long = maxExclusive - minInclusive
    val m: Long = n - 1L
    if ((n & m) == 0L) { // power of 2
      this.nextLong.map { (r: Long) => (r & m) + minInclusive }
    } else if (n > 0L) { // no underflow
      this.nextLongBounded(n).map { (r: Long) => r + minInclusive }
    } else { // range not representable as Long
      def go: Axn[Long] = this.nextLong.flatMapF { (r: Long) =>
        if ((r < minInclusive) || (r >= maxExclusive)) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def nextIntBounded(bound: Int): Axn[Int] = {
    require(bound > 0)
    val m: Int = bound - 1
    if ((bound & m) == 0) { // power of 2
      this.nextInt.map { (r: Int) => r & m }
    } else {
      def go: Axn[Int] = this.nextInt.flatMapF { (next: Int) =>
        val u: Int = next >>> 1
        val r: Int = u % bound
        if ((u + m - r) < 0) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def betweenInt(minInclusive: Int, maxExclusive: Int): Axn[Int] = {
    require(minInclusive < maxExclusive)
    val n: Int = maxExclusive - minInclusive
    val m: Int = n - 1
    if ((n & m) == 0) { // power of 2
      this.nextInt.map { (r: Int) => (r & m) + minInclusive }
    } else if (n > 0) { // no underflow
      this.nextIntBounded(n).map { (r: Int) => r + minInclusive }
    } else { // range not representable as Int
      def go: Axn[Int] = this.nextInt.flatMapF { (r: Int) =>
        if ((r < minInclusive) || (r >= maxExclusive)) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def nextDouble: Axn[Double] =
    this.nextLong.map(doubleFromLong)

  protected[this] final def doubleFromLong(n: Long): Double =
    (n >>> 11) * DoubleUlp

  final def betweenDouble(minInclusive: Double, maxExclusive: Double): Axn[Double] = {
    require(minInclusive < maxExclusive)
    this.nextDouble.map { (d: Double) =>
      val diff: Double = maxExclusive - minInclusive
      val r: Double = if (diff != java.lang.Double.POSITIVE_INFINITY) {
        // ok, no overflow:
        (d * diff) + minInclusive
      } else {
        // there was an overflow, so we're
        // scaling down, then up by 2.0:
        val maxHalf = maxExclusive / 2.0
        val minHalf = minInclusive / 2.0
        ((d * (maxHalf - minHalf)) + minHalf) * 2.0
      }
      if (r >= maxExclusive) { // this can happen due to rounding
        java.lang.Math.nextDown(maxExclusive)
      } else {
        r
      }
    }
  }

  /** Box-Muller transform / Marsaglia polar method */
  def nextGaussian: Axn[Double] = {
    (this.nextDouble * this.nextDouble).flatMapF { dd =>
      val (d1, d2) = dd
      val v1: Double = (2 * d1) - 1
      val v2: Double = (2 * d2) - 1
      val s: Double = (v1 * v1) + (v2 * v2)
      if ((s >= 1) || (s == 0)) {
        // retry:
        this.nextGaussian
      } else {
        val multiplier: Double =
          strictMathSqrt(-2 * strictMathLog(s) / s)
        Rxn.pure(v1 * multiplier)
        // NB: we actually generated 2 random Doubles,
        // (the other one is `v2 * multiplier`), but
        // we don't bother saving the other one for
        // next time (it probably doesn't worth it).
      }
    }
  }

  def nextFloat: Axn[Float] =
    nextInt.map { n => (n >>> 8) * FloatUlp }

  final def betweenFloat(minInclusive: Float, maxExclusive: Float): Axn[Float] = {
    require(minInclusive < maxExclusive)
    nextFloat.map { (f: Float) =>
      val diff: Float = maxExclusive - minInclusive
      val r: Float = if (diff != java.lang.Float.POSITIVE_INFINITY) {
        // ok, no overflow:
        (f * diff) + minInclusive
      } else {
        // there was an overflow, so we're
        // scaling down, then up by 2.0f:
        val maxHalf = maxExclusive / 2.0f
        val minHalf = minInclusive / 2.0f
        ((f * (maxHalf - minHalf)) + minHalf) * 2.0f
      }
      if (r >= maxExclusive) { // this can happen due to rounding
        java.lang.Math.nextDown(maxExclusive)
      } else {
        r
      }
    }
  }

  def nextBoolean: Axn[Boolean] =
    this.nextInt.map { r => r < 0 }

  final def nextAlphaNumeric: Axn[Char] = {
    RandomBase.nextAlphaNumeric(this.nextIntBounded)
  }

  final def nextPrintableChar: Axn[Char] = {
    this.betweenInt(MinPrintableIncl, MaxPrintableExcl).map { (i: Int) =>
      i.toChar
    }
  }

  /**
   * We also generate surrogates, unless they
   * would be illegal at that position.
   */
  def nextString(length: Int): Axn[String] = {
    require(length >= 0)
    if (length == 0) {
      Rxn.pure("")
    } else {
      Axn.unsafe.delay {
        val arr = new Array[Char](length)
        def write(idx: Int, value: Char): Axn[Unit] =
          Axn.unsafe.delay { arr(idx) = value }
        def go(idx: Int): Axn[Unit] = {
          if (idx < length) {
            if ((idx + 1) == length) {
              // last char, can't generate surrogates:
              this.nextNonSurrogate.flatMapF(write(idx, _))
            } else {
              // inside char, but can't generate a
              // low surrogate, because a surrogate
              // pair starts with a high surrogate:
              this.nextNormalOrHighSurrogate.flatMapF { (r: Char) =>
                write(idx, r) *> (
                  if (isHighSurrogate(r)) {
                    // we also generate its pair:
                    this.nextLowSurrogate.flatMapF(write(idx + 1, _)) *> go(idx + 2)
                  } else {
                    go(idx + 1)
                  }
                )
              }
            }
          } else {
            Rxn.unit
          }
        }
        go(0).flatMapF(_ => Axn.unsafe.delay(new String(arr)))
      }.flatten
    }
  }

  private[this] final def nextNormalOrHighSurrogate: Axn[Char] = {
    val bound: Int = (NumChars - NumLowSurrogates)
    this.nextIntBounded(bound).map { (r: Int) =>
      val res: Int = if (r >= MinLowSurrogate) {
        (r + NumLowSurrogates)
      } else {
        r
      }
      val c: Char = res.toChar
      _assert((c.toInt == res) && (!isLowSurrogate(c)))
      c
    }
  }

  private[this] final def nextLowSurrogate: Axn[Char] = {
    this.nextIntBounded(NumLowSurrogates).map { (r: Int) =>
      val c = (r + MinLowSurrogate).toChar
      _assert(isLowSurrogate(c))
      c
    }
  }

  private[this] final def nextNonSurrogate: Axn[Char] = {
    this.nextIntBounded(NumNonSurrogates).map { (r: Int) =>
      val res = if (r >= MinSurrogate) {
        r + NumSurrogates
      } else {
        r
      }
      val c = res.toChar
      _assert((c.toInt == res) && (!isHighSurrogate(c)) && (!isLowSurrogate(c)))
      c
    }
  }

  def shuffleList[A](l: List[A]): Axn[List[A]] = {
    if (l.length > 1) {
      Axn.unsafe.delay {
        val arr = ArrayBuffer.from(l)
        shuffleArray(arr) *> Axn.unsafe.delay(arr.toList)
      }.flatten
    } else {
      Rxn.pure(l)
    }
  }

  def shuffleVector[A](v: Vector[A]): Axn[Vector[A]] = {
    if (v.length > 1) {
      Axn.unsafe.delay {
        val arr = ArrayBuffer.from(v)
        shuffleArray(arr) *> Axn.unsafe.delay(arr.toVector)
      }.flatten
    } else {
      Rxn.pure(v)
    }
  }

  /** Fisher-Yates / Knuth shuffle */
  private[this] final def shuffleArray[A](arr: ArrayBuffer[A]): Axn[Unit] = {
    def swap(j: Int, i: Int): Unit = {
      val tmp = arr(j)
      arr(j) = arr(i)
      arr(i) = tmp
    }
    def go(i: Int): Axn[Unit] = {
      if (i > 0) {
        val bound: Int = i + 1
        this.nextIntBounded(bound).flatMapF { (j: Int) =>
          Axn.unsafe.delay(swap(j, i)) *> go(i - 1)
        }
      } else {
        Rxn.unit
      }
    }
    go(arr.length - 1)
  }
}

private object RandomBase {

  private[choam] def nextAlphaNumeric(nextIntBounded: Int => Axn[Int]): Axn[Char] = {
    nextIntBounded(LenAlphanumeric).map { (i: Int) =>
      Alphanumeric.charAt(i)
    }
  }

  private[choam] final val GoldenGamma =
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

  private[choam] final val MinLowSurrogate =
    0xdc00.toChar

  private[choam] final val MinSurrogate =
    0xd800.toChar

  private[choam] final val NumChars =
    65536

  private[choam] final val NumHighSurrogates =
    1024

  private[choam] final val NumLowSurrogates =
    1024

  private[choam] final val NumSurrogates =
    NumHighSurrogates + NumLowSurrogates

  private[choam] final val NumNonSurrogates =
    NumChars - NumSurrogates
}
