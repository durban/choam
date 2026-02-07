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
package random

import java.lang.Character.{ isHighSurrogate, isLowSurrogate }

import scala.collection.mutable.ArrayBuffer

import cats.effect.std.Random

import core.{ Rxn, RxnImpl }

/**
 * Common implementations of derived RNG methods.
 *
 * Some of these derived methods were adapted from the algorithms
 * in the public domain JSR-166 ThreadLocalRandom
 * (http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
 *
 * Note: some of the methods are implemented in terms of
 * each other, so subclasses *must* override either
 * `nextBytes` OR (`nextLong` AND `nextInt`).
 */
private abstract class RandomBase[R[a] >: RxnImpl[a]]
  extends RandomBasePlatform
  with Random[R] {

  import RandomBase._

  def nextLong: RxnImpl[Long] = {
    this.nextBytes(8).map { (arr: Array[Byte]) =>
      this.getLongAt0P(arr)
    }
  }

  def nextInt: RxnImpl[Int] = {
    this.nextLong.map { (r: Long) =>
      r.toInt
    }
  }

  // TODO: there should be a (protected) allocation-less
  // TODO: version of `nextBytes` (for performance)

  def nextBytes(n: Int): RxnImpl[Array[Byte]] =
    nextBytesInternal(n, this.nextLong)

  protected[this] def nextBytesInternal(n: Int, nextLong: RxnImpl[Long]): RxnImpl[Array[Byte]] = {
    require(n >= 0)

    /* Puts the last (at most 7) bytes into buf */
    @tailrec
    def putLastBytes(arr: Array[Byte], nBytes: Int, idx: Int, r: Long): Unit = {
      if (nBytes > 0) {
        arr(idx) = r.toByte
        putLastBytes(arr, nBytes - 1, idx = idx + 1, r = r >>> 8)
      }
    }

    def go(arr: Array[Byte], idx: Int): RxnImpl[Unit] = {
      if (idx < n) {
        val remaining = n - idx
        nextLong.flatMap { (r: Long) =>
          if (remaining >= 8) {
            Rxn.unsafe.delay(this.putLongAtIdxP(arr, idx, r)) *> go(arr, idx + 8)
          } else {
            Rxn.unsafe.delay(putLastBytes(arr, nBytes = remaining, idx = idx, r = r))
          }
        }
      } else {
        Rxn.unitImpl
      }
    }

    Rxn.unsafe.suspendImpl {
      val arr = new Array[Byte](n)
      go(arr, 0).as(arr)
    }
  }

  def nextLongBounded(bound: Long): RxnImpl[Long] = {
    // TODO: take a look at https://arxiv.org/abs/1805.10941
    require(bound > 0L)
    val m: Long = bound - 1L
    if ((bound & m) == 0L) { // power of 2
      this.nextLong.map { (r: Long) => r & m }
    } else {
      def go: RxnImpl[Long] = this.nextLong.flatMap { (next: Long) =>
        val u: Long = next >>> 1
        val r: Long = u % bound
        if ((u + m - r) < 0L) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def betweenLong(minInclusive: Long, maxExclusive: Long): RxnImpl[Long] = {
    require(minInclusive < maxExclusive)
    val n: Long = maxExclusive - minInclusive
    val m: Long = n - 1L
    if ((n & m) == 0L) { // power of 2
      this.nextLong.map { (r: Long) => (r & m) + minInclusive }
    } else if (n > 0L) { // no underflow
      this.nextLongBounded(n).map { (r: Long) => r + minInclusive }
    } else { // range not representable as Long
      def go: RxnImpl[Long] = this.nextLong.flatMap { (r: Long) =>
        if ((r < minInclusive) || (r >= maxExclusive)) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def nextIntBounded(bound: Int): RxnImpl[Int] = {
    require(bound > 0)
    val m: Int = bound - 1
    if ((bound & m) == 0) { // power of 2
      this.nextInt.map { (r: Int) => r & m }
    } else {
      def go: RxnImpl[Int] = this.nextInt.flatMap { (next: Int) =>
        val u: Int = next >>> 1
        val r: Int = u % bound
        if ((u + m - r) < 0) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def betweenInt(minInclusive: Int, maxExclusive: Int): RxnImpl[Int] = {
    require(minInclusive < maxExclusive)
    val n: Int = maxExclusive - minInclusive
    val m: Int = n - 1
    if ((n & m) == 0) { // power of 2
      this.nextInt.map { (r: Int) => (r & m) + minInclusive }
    } else if (n > 0) { // no underflow
      this.nextIntBounded(n).map { (r: Int) => r + minInclusive }
    } else { // range not representable as Int
      def go: RxnImpl[Int] = this.nextInt.flatMap { (r: Int) =>
        if ((r < minInclusive) || (r >= maxExclusive)) go
        else Rxn.pure(r)
      }
      go
    }
  }

  def nextDouble: RxnImpl[Double] =
    this.nextLong.map(doubleFromLong)

  protected[this] final def doubleFromLong(n: Long): Double =
    (n >>> 11) * DoubleUlp

  final def betweenDouble(minInclusive: Double, maxExclusive: Double): RxnImpl[Double] = {
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
  def nextGaussian: RxnImpl[Double] = {
    (this.nextDouble * this.nextDouble).flatMap { dd =>
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

  def nextFloat: RxnImpl[Float] =
    nextInt.map { n => (n >>> 8) * FloatUlp }

  final def betweenFloat(minInclusive: Float, maxExclusive: Float): RxnImpl[Float] = {
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

  def nextBoolean: RxnImpl[Boolean] =
    this.nextInt.map { r => r < 0 }

  final def nextAlphaNumeric: RxnImpl[Char] = {
    RandomBase.nextAlphaNumeric(this.nextIntBounded)
  }

  final def nextPrintableChar: RxnImpl[Char] = {
    this.betweenInt(MinPrintableIncl, MaxPrintableExcl).map { (i: Int) =>
      i.toChar
    }
  }

  /**
   * We also generate surrogates, unless they
   * would be illegal at that position.
   */
  def nextString(length: Int): RxnImpl[String] = {
    require(length >= 0)
    if (length == 0) {
      Rxn.pureImpl("")
    } else {
      Rxn.unsafe.suspendImpl {
        val arr = new Array[Char](length)
        def write(idx: Int, value: Char): Rxn[Unit] =
          Rxn.unsafe.delay { arr(idx) = value }
        def go(idx: Int): Rxn[Unit] = {
          if (idx < length) {
            if ((idx + 1) == length) {
              // last char, can't generate surrogates:
              this.nextNonSurrogate.flatMap(write(idx, _))
            } else {
              // inside char, but can't generate a
              // low surrogate, because a surrogate
              // pair starts with a high surrogate:
              this.nextNormalOrHighSurrogate.flatMap { (r: Char) =>
                write(idx, r) *> (
                  if (isHighSurrogate(r)) {
                    // we also generate its pair:
                    this.nextLowSurrogate.flatMap(write(idx + 1, _)) *> go(idx + 2)
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
        go(0).flatMap(_ => Rxn.unsafe.delay(new String(arr)))
      }
    }
  }

  private[this] final def nextNormalOrHighSurrogate: Rxn[Char] = {
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

  private[this] final def nextLowSurrogate: Rxn[Char] = {
    this.nextIntBounded(NumLowSurrogates).map { (r: Int) =>
      val c = (r + MinLowSurrogate).toChar
      _assert(isLowSurrogate(c))
      c
    }
  }

  private[this] final def nextNonSurrogate: Rxn[Char] = {
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

  def shuffleList[A](l: List[A]): RxnImpl[List[A]] = {
    if (l.length > 1) {
      Rxn.unsafe.suspendImpl {
        val arr = ArrayBuffer.from(l)
        shuffleArray(arr) *> Rxn.unsafe.delay(arr.toList)
      }
    } else {
      Rxn.pureImpl(l)
    }
  }

  def shuffleVector[A](v: Vector[A]): RxnImpl[Vector[A]] = {
    if (v.length > 1) {
      Rxn.unsafe.suspendImpl {
        val arr = ArrayBuffer.from(v)
        shuffleArray(arr) *> Rxn.unsafe.delay(arr.toVector)
      }
    } else {
      Rxn.pureImpl(v)
    }
  }

  /** Fisher-Yates / Knuth shuffle */
  private[this] final def shuffleArray[A](arr: ArrayBuffer[A]): Rxn[Unit] = {
    def swap(j: Int, i: Int): Unit = {
      val tmp = arr(j)
      arr(j) = arr(i)
      arr(i) = tmp
    }
    def go(i: Int): Rxn[Unit] = {
      if (i > 0) {
        val bound: Int = i + 1
        this.nextIntBounded(bound).flatMap { (j: Int) =>
          Rxn.unsafe.delay(swap(j, i)) *> go(i - 1)
        }
      } else {
        Rxn.unit
      }
    }
    go(arr.length - 1)
  }

  protected[this] final def staffordMix13(s: Long): Long = {
    internal.mcas.Consts.staffordMix13(s)
  }

  protected[this] final def staffordMix04(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 33)
    n *= 0x62a9d9ed799705f5L
    n ^= (n >>> 28)
    n *= 0xcb24d0a5c88c35b3L
    n ^= (n >>> 32)
    n
  }
}

private object RandomBase {

  private[choam] def nextAlphaNumeric(nextIntBounded: Int => RxnImpl[Int]): RxnImpl[Char] = {
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
