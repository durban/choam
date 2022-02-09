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

import java.nio.{ ByteBuffer, ByteOrder }

import cats.effect.std.Random

/**
 * Uses `RandomBase` for everything, implements
 * only the absolutely necessary methods. (For
 * testing and benchmarking.)
 */
private final class MinimalRandom extends RandomBase {

  def nextGaussian: Axn[Double] =
    sys.error("TODO")

  def nextInt: Axn[Int] =
    sys.error("TODO")

  def nextLong: Axn[Long] =
    sys.error("TODO")

  def nextString(length: Int): Axn[String] =
    sys.error("TODO")

  def shuffleList[A](l: List[A]): Axn[List[A]] =
    sys.error("TODO")

  def shuffleVector[A](v: Vector[A]): Axn[Vector[A]] =
    sys.error("TODO")
}

/**
 * Common implementations of derived RNG methods.
 *
 * Some of these derived methods were adapted from the algorithms
 * in the public domain JSR-166 ThreadLocalRandom
 * (http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
 */
private trait RandomBase extends Random[Axn] {

  import RandomBase._

  def nextBytes(n: Int): Axn[Array[Byte]] = {
    require(n >= 0)

    /* Puts the last (at most 7) bytes into buf */
    @tailrec
    def putLastBytes(buf: ByteBuffer, nBytes: Int, r: Long): Unit = {
      if (nBytes > 0) {
        buf.put(r.toByte)
        putLastBytes(buf, nBytes - 1, r >>> 8)
      }
    }

    def go(buf: ByteBuffer, rem: Int): Axn[Unit] = {
      if (rem > 0) {
        this.nextLong.flatMapF { (r: Long) =>
          if (rem >= 8) {
            Axn.unsafe.delay(buf.putLong(r)) *> go(buf, rem = buf.remaining)
          } else {
            Axn.unsafe.delay(putLastBytes(buf = buf, nBytes = rem, r = r))
          }
        }
      } else {
        Rxn.unit
      }
    }

    Axn.unsafe.delay {
      val arr = new Array[Byte](n)
      val buf = ByteBuffer.wrap(arr)
      buf.order(ByteOrder.LITTLE_ENDIAN)
      go(buf, rem = buf.remaining).as(arr)
    }.flatten
  }

  def nextLongBounded(bound: Long): Axn[Long] = {
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
    import java.lang.Double.{ doubleToLongBits, longBitsToDouble }
    require(minInclusive < maxExclusive)
    this.nextDouble.map { (d: Double) =>
      val r: Double = (d * (maxExclusive - minInclusive)) + minInclusive
      if (r >= maxExclusive) { // this can happen due to rounding
        val correction = if (maxExclusive < 0.0) 1L else -1L
        longBitsToDouble(doubleToLongBits(maxExclusive) + correction)
      } else {
        r
      }
    }
  }

  def nextFloat: Axn[Float] =
    nextInt.map { n => (n >>> 8) * FloatUlp }

  final def betweenFloat(minInclusive: Float, maxExclusive: Float): Axn[Float] = {
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
}

private object RandomBase {

  private[choam] def nextAlphaNumeric(nextIntBounded: Int => Axn[Int]): Axn[Char] = {
    nextIntBounded(LenAlphanumeric).map { (i: Int) =>
      Alphanumeric.charAt(i)
    }
  }

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
}
