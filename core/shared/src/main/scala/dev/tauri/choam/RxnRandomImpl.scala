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

import CompatPlatform.SecureRandom

private object RxnRandomImplSecure {

  def unsafe(): Random[Axn] = {
    val sr = new SecureRandom
    // force seeding the generator here:
    val dummy = new Array[Byte](4)
    sr.nextBytes(dummy)
    new RxnRandomImplSecure(sr)
  }
}

private final class RxnRandomImplSecure private (private[this] val jRnd: SecureRandom)
  extends RandomBase {

  import Rxn.unsafe.delay

  final override def nextDouble: Axn[Double] =
    delay { _ => jRnd.nextDouble() }

  final override def nextFloat: Axn[Float] =
    delay { _ => jRnd.nextFloat() }

  final def nextGaussian: Axn[Double] =
    delay { _ => jRnd.nextGaussian() }

  final def nextInt: Axn[Int] =
    delay { _ => jRnd.nextInt() }

  final override def nextIntBounded(n: Int): Axn[Int] =
    delay { _ => jRnd.nextInt(n) }

  final def nextLong: Axn[Long] =
    delay { _ => jRnd.nextLong() }

  final override def nextBoolean: Axn[Boolean] =
    delay { _ => jRnd.nextBoolean() }
}

private object RxnRandomImplCtxSupport {

  def unsafe(): Random[Axn] = {
    new RxnRandomImplCtxSupport
  }
}

private final class RxnRandomImplCtxSupport private ()
  extends RandomBase {

  import Rxn.unsafe.delayContext

  final override def nextDouble: Axn[Double] =
    delayContext { ctx => ctx.random.nextDouble() }

  final override def nextFloat: Axn[Float] =
    delayContext { ctx => ctx.random.nextFloat() }

  final def nextGaussian: Axn[Double] =
    delayContext { ctx => ctx.random.nextGaussian() }

  final def nextInt: Axn[Int] =
    delayContext { ctx => ctx.random.nextInt() }

  final override def nextIntBounded(n: Int): Axn[Int] =
    delayContext { ctx => ctx.random.nextInt(n) }

  final def nextLong: Axn[Long] =
    delayContext { ctx => ctx.random.nextLong() }

  final override def nextLongBounded(n: Long): Axn[Long] =
    delayContext { ctx => ctx.random.nextLong(n) }

  final override def nextBoolean: Axn[Boolean] =
    delayContext { ctx => ctx.random.nextBoolean() }
}
