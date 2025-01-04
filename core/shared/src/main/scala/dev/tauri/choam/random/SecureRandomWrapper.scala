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

import java.security.{ SecureRandom => JSecureRandom }

import cats.effect.std.SecureRandom

private object SecureRandomWrapper {
  @deprecated("Don't use SecureRandomWrapper, because it may block", since = "0.4")
  def unsafe(): SecureRandom[Axn] = {
    val sr = new JSecureRandom
    // force seeding the generator here:
    val dummy = new Array[Byte](4)
    sr.nextBytes(dummy)
    new SecureRandomWrapper(sr)
  }
}

/**
 * Implements [[cats.effect.std.SecureRandom]] by wrapping
 * a [[java.security.SecureRandom]]. Don't use, because
 * these typically block. Preserved only for testing and
 * benchmarking.
 */
private final class SecureRandomWrapper private (jRnd: JSecureRandom)
  extends RandomBase with SecureRandom[Axn] {

  import Axn.unsafe.delay

  // Override these, to use the secure impl:

  final override def nextInt: Axn[Int] =
    delay { jRnd.nextInt() }

  final override def nextLong: Axn[Long] =
    delay { jRnd.nextLong() }

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
