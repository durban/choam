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

import java.util.UUID

import cats.effect.std.{ Random, SecureRandom }

import core.Rxn

package object random {

  private[choam] final def newUuidImpl: core.RxnImpl[UUID] = {
    core.Rxn.unsafe.delayContextImpl(RxnUuidGen.unsafeRandomUuidInternal)
  }

  private[random] def uuidFromRandomBytes(buff: Array[Byte]): UUID = {
    RxnUuidGen.uuidFromRandomBytes(buff)
  }

  private[choam] final def newFastRandom: Random[Rxn] =
    new RxnThreadLocalRandom[Rxn]

  private[choam] final def newSecureRandom: SecureRandom[Rxn] =
    new SecureRandomRxn[Rxn]

  private[choam] final def deterministicRandom(initialSeed: Long, str: AllocationStrategy): Rxn[SplittableRandom[Rxn]] =
    DeterministicRandom(initialSeed, str)

  // TODO: do we need this?
  private[choam] def minimalRandom1(initialSeed: Long): Rxn[Random[Rxn]] =
    Rxn.unsafe.delayContext { ctx => MinimalRandom.unsafe1(initialSeed, ctx.refIdGen) }

  // TODO: do we need this?
  private[choam] def minimalRandom2(initialSeed: Long): Rxn[Random[Rxn]] =
    Rxn.unsafe.delayContext { ctx => MinimalRandom.unsafe2(initialSeed, ctx.refIdGen) }
}
