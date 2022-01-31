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

/**
 * Common implementations of derived RNG methods.
 *
 * Some of these derived methods were adapted from the algorithms
 * in the public domain JSR-166 ThreadLocalRandom
 * (http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
 */
private trait RandomBase extends Random[Axn] {

  import RandomBase._

  final override def nextBoolean: Axn[Boolean] =
    this.nextInt.map { r => r < 0 }

  final override def nextAlphaNumeric: Axn[Char] = {
    this.nextIntBounded(LenAlphanumeric).map { (i: Int) =>
      Alphanumeric.charAt(i)
    }
  }
}

private object RandomBase {

  private final val Alphanumeric =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

  private final val LenAlphanumeric =
    62
}
