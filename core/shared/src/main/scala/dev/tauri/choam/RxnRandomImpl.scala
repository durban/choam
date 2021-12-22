/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.{ Random => JRandom }
import java.util.concurrent.ThreadLocalRandom

import scala.util.{ Random => SRandom }

import cats.effect.std.Random

private object RxnRandomImpl {

  def makeNewThreadLocalRandom[X](): Random[Rxn[X, *]] = {
    new RxnRandomImpl[X] {
      protected final override def jRnd: JRandom =
        ThreadLocalRandom.current() // TODO: try using `Rxn.unsafe.context` and benchmark!
    }
  }

  def makeNewSecureRandom[X](): Random[Rxn[X, *]] = {
    new RxnRandomImpl[X] {
      protected final override val jRnd: JRandom =
        new CompatPlatform.SecureRandom
        // TODO: maybe force (re)seeding here (it might block?)
    }
  }

  // TODO: Maybe create a deterministic random generator, which has
  // TODO: its state in a `Ref`, and thus updated transactionally.
}

private abstract class RxnRandomImpl[X] private ()
 extends Random[Rxn[X, *]] {

  import Rxn.unsafe.delay

  protected def jRnd: JRandom

  protected def sRnd: SRandom =
    new SRandom(jRnd) // TODO: can't cache due to ThreadLocalRandom

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
