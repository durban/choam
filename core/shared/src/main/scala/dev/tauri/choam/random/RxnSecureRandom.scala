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

import cats.effect.std.Random

import core.CompatPlatform.SecureRandom

private[choam] object RxnSecureRandom {
  def unsafe(): Random[Axn] = {
    val sr = new SecureRandom
    // force seeding the generator here:
    val dummy = new Array[Byte](4)
    sr.nextBytes(dummy)
    new RxnSecureRandom(sr)
  }
}

private final class RxnSecureRandom private (private[this] val jRnd: SecureRandom)
  extends RandomBase {

  import Axn.unsafe.delay

  final def nextInt: Axn[Int] =
    delay { jRnd.nextInt() }

  final def nextLong: Axn[Long] =
    delay { jRnd.nextLong() }

  // Override these, to use the secure impl:

  final override def nextIntBounded(n: Int): Axn[Int] =
    delay { jRnd.nextInt(n) }

  final override def nextDouble: Axn[Double] =
    delay { jRnd.nextDouble() }

  final override def nextGaussian: Axn[Double] =
    delay { jRnd.nextGaussian() }

  final override def nextFloat: Axn[Float] =
    delay { jRnd.nextFloat() }

  final override def nextBoolean: Axn[Boolean] =
    delay { jRnd.nextBoolean() }
}
