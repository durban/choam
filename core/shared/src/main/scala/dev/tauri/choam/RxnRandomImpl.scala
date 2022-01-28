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

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

import scala.util.{ Random => SRandom }

import cats.effect.std.Random

import CompatPlatform.SecureRandom

private object RxnRandomImplSecure {

  def unsafe[X](): Random[Rxn[X, *]] = {
    val sr = new SecureRandom
    // force seeding the generator here:
    val dummy = new Array[Byte](4)
    sr.nextBytes(dummy)
    new RxnRandomImplSecure[X](sr)
  }
}

private object RxnRandomImplThreadLocal {

  def unsafe[X](): Random[Rxn[X, *]] = {
    new RxnRandomImplThreadLocal[X]
  }
}

private final class RxnRandomImplSecure[X] private (private[this] val jRnd: SecureRandom)
  extends Random[Rxn[X, *]] {

  import Rxn.unsafe.delay

  private[this] val sRnd: SRandom =
    new SRandom(jRnd)

  final override def betweenDouble(minInclusive: Double, maxExclusive: Double): Rxn[X, Double] =
    delay { _ => sRnd.between(minInclusive, maxExclusive) }

  final override def betweenFloat(minInclusive: Float, maxExclusive: Float): Rxn[X, Float] =
    delay { _ => sRnd.between(minInclusive, maxExclusive) }

  final override def betweenInt(minInclusive: Int, maxExclusive: Int): Rxn[X, Int] =
    delay { _ => sRnd.between(minInclusive, maxExclusive) }

  final override def betweenLong(minInclusive: Long, maxExclusive: Long): Rxn[X, Long] =
    delay { _ => sRnd.between(minInclusive, maxExclusive) }

  final override def nextAlphaNumeric: Rxn[X, Char] =
    delay { _ => sRnd.alphanumeric.head } // TODO: optimize

  final override def nextBoolean: Rxn[X, Boolean] =
    delay { _ => jRnd.nextBoolean() }

  final override def nextBytes(n: Int): Rxn[X, Array[Byte]] = {
    require(n >= 0)
    delay { _ => sRnd.nextBytes(n) }
  }

  final override def nextDouble: Rxn[X, Double] =
    delay { _ => jRnd.nextDouble() }

  final override def nextFloat: Rxn[X, Float] =
    delay { _ => jRnd.nextFloat() }

  final override def nextGaussian: Rxn[X, Double] =
    delay { _ => jRnd.nextGaussian() }

  final override def nextInt: Rxn[X, Int] =
    delay { _ => jRnd.nextInt() }

  final override def nextIntBounded(n: Int): Rxn[X, Int] =
    delay { _ => jRnd.nextInt(n) }

  final override def nextLong: Rxn[X, Long] =
    delay { _ => jRnd.nextLong() }

  final override def nextLongBounded(n: Long): Rxn[X, Long] =
    delay { _ => sRnd.nextLong(n) }

  final override def nextPrintableChar: Rxn[X, Char] =
    delay { _ => sRnd.nextPrintableChar() }

  final override def nextString(length: Int): Rxn[X, String] =
    delay { _ => sRnd.nextString(length) }

  final override def shuffleList[A](l: List[A]): Rxn[X, List[A]] =
    delay { _ => sRnd.shuffle[A, List[A]](l) }

  final override def shuffleVector[A](v: Vector[A]): Rxn[X, Vector[A]] =
    delay { _ => sRnd.shuffle[A, Vector[A]](v) }
}

private final class RxnRandomImplThreadLocal[X] private ()
  extends Random[Rxn[X, *]] {

  import Rxn.unsafe.delayContext

  private[this] final def sRnd(jRnd: ThreadLocalRandom): SRandom = {
    // TODO: Can't cache because ThreadLocalRandom.current()
    // TODO: is not guaranteed to always return the same object.
    // TODO: (On OpenJDK 11+ it seems to always do, but the
    // TODO: documentation doesn't guarantee this behavior.)
    new SRandom(jRnd)
  }

  final override def betweenDouble(minInclusive: Double, maxExclusive: Double): Rxn[X, Double] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def betweenFloat(minInclusive: Float, maxExclusive: Float): Rxn[X, Float] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def betweenInt(minInclusive: Int, maxExclusive: Int): Rxn[X, Int] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def betweenLong(minInclusive: Long, maxExclusive: Long): Rxn[X, Long] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def nextAlphaNumeric: Rxn[X, Char] =
    delayContext { ctx => sRnd(ctx.random).alphanumeric.head } // TODO: optimize

  final override def nextBoolean: Rxn[X, Boolean] =
    delayContext { ctx => ctx.random.nextBoolean() }

  final override def nextBytes(n: Int): Rxn[X, Array[Byte]] = {
    require(n >= 0)
    delayContext { ctx => sRnd(ctx.random).nextBytes(n) }
  }

  final override def nextDouble: Rxn[X, Double] =
    delayContext { ctx => ctx.random.nextDouble() }

  final override def nextFloat: Rxn[X, Float] =
    delayContext { ctx => ctx.random.nextFloat() }

  final override def nextGaussian: Rxn[X, Double] =
    delayContext { ctx => ctx.random.nextGaussian() }

  final override def nextInt: Rxn[X, Int] =
    delayContext { ctx => ctx.random.nextInt() }

  final override def nextIntBounded(n: Int): Rxn[X, Int] =
    delayContext { ctx => ctx.random.nextInt(n) }

  final override def nextLong: Rxn[X, Long] =
    delayContext { ctx => ctx.random.nextLong() }

  final override def nextLongBounded(n: Long): Rxn[X, Long] =
    delayContext { ctx => ctx.random.nextLong(n) }

  final override def nextPrintableChar: Rxn[X, Char] =
    delayContext { ctx => sRnd(ctx.random).nextPrintableChar() }

  final override def nextString(length: Int): Rxn[X, String] =
    delayContext { ctx => sRnd(ctx.random).nextString(length) }

  final override def shuffleList[A](l: List[A]): Rxn[X, List[A]] =
    delayContext { ctx => sRnd(ctx.random).shuffle[A, List[A]](l) }

  final override def shuffleVector[A](v: Vector[A]): Rxn[X, Vector[A]] =
    delayContext { ctx => sRnd(ctx.random).shuffle[A, Vector[A]](v) }
}

private object RxnRandomImplThreadLocalCached {

  def unsafe[X](): Random[Rxn[X, *]] = {
    new RxnRandomImplThreadLocalCached[X]
  }
}

private final class RxnRandomImplThreadLocalCached[X] private ()
  extends AtomicReference[SRandom] // (null)
  with Random[Rxn[X, *]] { sRndHolder =>

  import Rxn.unsafe.delayContext

  private[this] final def sRnd(jRnd: ThreadLocalRandom): SRandom = {
    val cached = sRndHolder.get()
    if ((cached ne null) && (cached.self eq jRnd)) {
      cached
    } else {
      val newWrapper = new SRandom(jRnd)
      sRndHolder.compareAndSet(cached, newWrapper)
      // we ignore a failed CAS, as we can use
      // the newly created wrapper, it just will
      // not be cached
      newWrapper
    }
  }

  final override def betweenDouble(minInclusive: Double, maxExclusive: Double): Rxn[X, Double] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def betweenFloat(minInclusive: Float, maxExclusive: Float): Rxn[X, Float] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def betweenInt(minInclusive: Int, maxExclusive: Int): Rxn[X, Int] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def betweenLong(minInclusive: Long, maxExclusive: Long): Rxn[X, Long] =
    delayContext { ctx => sRnd(ctx.random).between(minInclusive, maxExclusive) }

  final override def nextAlphaNumeric: Rxn[X, Char] =
    delayContext { ctx => sRnd(ctx.random).alphanumeric.head } // TODO: optimize

  final override def nextBoolean: Rxn[X, Boolean] =
    delayContext { ctx => ctx.random.nextBoolean() }

  final override def nextBytes(n: Int): Rxn[X, Array[Byte]] = {
    require(n >= 0)
    delayContext { ctx => sRnd(ctx.random).nextBytes(n) }
  }

  final override def nextDouble: Rxn[X, Double] =
    delayContext { ctx => ctx.random.nextDouble() }

  final override def nextFloat: Rxn[X, Float] =
    delayContext { ctx => ctx.random.nextFloat() }

  final override def nextGaussian: Rxn[X, Double] =
    delayContext { ctx => ctx.random.nextGaussian() }

  final override def nextInt: Rxn[X, Int] =
    delayContext { ctx => ctx.random.nextInt() }

  final override def nextIntBounded(n: Int): Rxn[X, Int] =
    delayContext { ctx => ctx.random.nextInt(n) }

  final override def nextLong: Rxn[X, Long] =
    delayContext { ctx => ctx.random.nextLong() }

  final override def nextLongBounded(n: Long): Rxn[X, Long] =
    delayContext { ctx => ctx.random.nextLong(n) }

  final override def nextPrintableChar: Rxn[X, Char] =
    delayContext { ctx => sRnd(ctx.random).nextPrintableChar() }

  final override def nextString(length: Int): Rxn[X, String] =
    delayContext { ctx => sRnd(ctx.random).nextString(length) }

  final override def shuffleList[A](l: List[A]): Rxn[X, List[A]] =
    delayContext { ctx => sRnd(ctx.random).shuffle[A, List[A]](l) }

  final override def shuffleVector[A](v: Vector[A]): Rxn[X, Vector[A]] =
    delayContext { ctx => sRnd(ctx.random).shuffle[A, Vector[A]](v) }
}

private object RxnRandomImplCtxSupport {

  def unsafe[X](): Random[Rxn[X, *]] = {
    new RxnRandomImplCtxSupport[X]
  }
}

private final class RxnRandomImplCtxSupport[X] private ()
  extends Random[Rxn[X, *]] {

  import Rxn.unsafe.delayContext

  final override def betweenDouble(minInclusive: Double, maxExclusive: Double): Rxn[X, Double] =
    delayContext { ctx => ctx.randomWrapper.between(minInclusive, maxExclusive) }

  final override def betweenFloat(minInclusive: Float, maxExclusive: Float): Rxn[X, Float] =
    delayContext { ctx => ctx.randomWrapper.between(minInclusive, maxExclusive) }

  final override def betweenInt(minInclusive: Int, maxExclusive: Int): Rxn[X, Int] =
    delayContext { ctx => ctx.randomWrapper.between(minInclusive, maxExclusive) }

  final override def betweenLong(minInclusive: Long, maxExclusive: Long): Rxn[X, Long] =
    delayContext { ctx => ctx.randomWrapper.between(minInclusive, maxExclusive) }

  final override def nextAlphaNumeric: Rxn[X, Char] =
    delayContext { ctx => ctx.randomWrapper.alphanumeric.head } // TODO: optimize

  final override def nextBoolean: Rxn[X, Boolean] =
    delayContext { ctx => ctx.random.nextBoolean() }

  final override def nextBytes(n: Int): Rxn[X, Array[Byte]] = {
    require(n >= 0)
    delayContext { ctx => ctx.randomWrapper.nextBytes(n) }
  }

  final override def nextDouble: Rxn[X, Double] =
    delayContext { ctx => ctx.random.nextDouble() }

  final override def nextFloat: Rxn[X, Float] =
    delayContext { ctx => ctx.random.nextFloat() }

  final override def nextGaussian: Rxn[X, Double] =
    delayContext { ctx => ctx.random.nextGaussian() }

  final override def nextInt: Rxn[X, Int] =
    delayContext { ctx => ctx.random.nextInt() }

  final override def nextIntBounded(n: Int): Rxn[X, Int] =
    delayContext { ctx => ctx.random.nextInt(n) }

  final override def nextLong: Rxn[X, Long] =
    delayContext { ctx => ctx.random.nextLong() }

  final override def nextLongBounded(n: Long): Rxn[X, Long] =
    delayContext { ctx => ctx.random.nextLong(n) }

  final override def nextPrintableChar: Rxn[X, Char] =
    delayContext { ctx => ctx.randomWrapper.nextPrintableChar() }

  final override def nextString(length: Int): Rxn[X, String] =
    delayContext { ctx => ctx.randomWrapper.nextString(length) }

  final override def shuffleList[A](l: List[A]): Rxn[X, List[A]] =
    delayContext { ctx => ctx.randomWrapper.shuffle[A, List[A]](l) }

  final override def shuffleVector[A](v: Vector[A]): Rxn[X, Vector[A]] =
    delayContext { ctx => ctx.randomWrapper.shuffle[A, Vector[A]](v) }
}
