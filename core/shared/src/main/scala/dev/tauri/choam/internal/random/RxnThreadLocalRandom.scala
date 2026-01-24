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

import java.util.concurrent.ThreadLocalRandom

import core.Rxn

private final class RxnThreadLocalRandom
  extends RandomBase {

  import Rxn.unsafe.delayContext

  // override these, because TLR is faster:

  final override def nextLong: Rxn[Long] =
    delayContext { ctx => (ctx.random : ThreadLocalRandom).nextLong() }

  final override def nextInt: Rxn[Int] =
    delayContext { ctx => ctx.random.nextInt() }

  final override def nextLongBounded(n: Long): Rxn[Long] =
    delayContext { ctx => ctx.random.nextLong(n) }

  final override def nextIntBounded(n: Int): Rxn[Int] =
    delayContext { ctx => ctx.random.nextInt(n) }

  final override def nextDouble: Rxn[Double] =
    delayContext { ctx => ctx.random.nextDouble() }

  final override def nextGaussian: Rxn[Double] =
    delayContext { ctx => ctx.random.nextGaussian() }

  final override def nextFloat: Rxn[Float] =
    delayContext { ctx => ctx.random.nextFloat() }

  final override def nextBoolean: Rxn[Boolean] =
    delayContext { ctx => ctx.random.nextBoolean() }
}
