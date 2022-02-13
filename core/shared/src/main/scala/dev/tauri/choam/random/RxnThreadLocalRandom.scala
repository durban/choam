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
package random

import java.util.concurrent.ThreadLocalRandom

import cats.effect.std.Random

private[choam] object RxnThreadLocalRandom {
  def unsafe(): Random[Axn] = {
    new RxnThreadLocalRandom
  }
}

private final class RxnThreadLocalRandom private ()
  extends RandomBase {

  import Rxn.unsafe.delayContext

  final def nextLong: Axn[Long] =
    delayContext { ctx => (ctx.random : ThreadLocalRandom).nextLong() }

  final def nextInt: Axn[Int] =
    delayContext { ctx => ctx.random.nextInt() }

  // override these, because TLR is faster:

  final override def nextLongBounded(n: Long): Axn[Long] =
    delayContext { ctx => ctx.random.nextLong(n) }

  final override def nextIntBounded(n: Int): Axn[Int] =
    delayContext { ctx => ctx.random.nextInt(n) }

  final override def nextDouble: Axn[Double] =
    delayContext { ctx => ctx.random.nextDouble() }

  final override def nextGaussian: Axn[Double] =
    delayContext { ctx => ctx.random.nextGaussian() }

  final override def nextFloat: Axn[Float] =
    delayContext { ctx => ctx.random.nextFloat() }

  final override def nextBoolean: Axn[Boolean] =
    delayContext { ctx => ctx.random.nextBoolean() }
}