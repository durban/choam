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
package core

import java.util.UUID

import bobcats.unsafe.SecureRandom

private[choam] abstract class RxnCompanionPlatform { this: Rxn.type => // TODO: make it private

  private[core] final type ExchangerImpl[A, B] = ExchangerImplJs[A, B]

  private[choam] final type ExStatMap = Map[Exchanger.Key, AnyRef]

  // UUID generation:
  // (because https://github.com/scala-js/scala-js/blob/v1.8.0/javalib/src/main/scala/java/util/UUID.scala#L139)

  private[this] final lazy val _secureRandom: SecureRandom =
    new SecureRandom

  // 4 bits; version 4, i.e., random
  private[this] final val VERSION = 0x0000000000004000L
  private[this] final val VERSION_MASK = 0xFFFFFFFFFFFF0FFFL

  // 2 bits; variant 0b10, i.e., RFC 4122
  private[this] final val VARIANT = 0x8000000000000000L
  private[this] final val VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL

  private[core] final def rxnRandomUUID[X]: Rxn[X, UUID] = {
    this.unsafe.delay { _ =>
      val sr = this._secureRandom
      val msb = (sr.nextLong() & VERSION_MASK) | VERSION
      val lsb = (sr.nextLong() & VARIANT_MASK) | VARIANT
      new UUID(msb, lsb)
    }
  }
}
