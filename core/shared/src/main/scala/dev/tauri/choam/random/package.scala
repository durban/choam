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

import java.util.UUID

import cats.effect.std.{ Random, SecureRandom }

package object random {

  private[choam] final def newUuidImpl: core.RxnImpl[Any, UUID] = {
    core.Rxn.unsafe.delayContextImpl(RxnUuidGen.unsafeRandomUuidInternal)
  }

  private[random] def uuidFromRandomBytes(buff: Array[Byte]): UUID = {
    RxnUuidGen.uuidFromRandomBytes(buff)
  }

  private[choam] final def newFastRandom: Random[Axn] =
    new RxnThreadLocalRandom

  private[choam] final def newSecureRandom: SecureRandom[Axn] =
    new SecureRandomRxn

  private[choam] final def deterministicRandom(initialSeed: Long, str: Ref.AllocationStrategy): Axn[SplittableRandom[Axn]] =
    DeterministicRandom(initialSeed, str)

  // TODO: do we need this?
  private[choam] def minimalRandom1(initialSeed: Long): Axn[Random[Axn]] =
    Axn.unsafe.delayContext { ctx => MinimalRandom.unsafe1(initialSeed, ctx.refIdGen) }

  // TODO: do we need this?
  private[choam] def minimalRandom2(initialSeed: Long): Axn[Random[Axn]] =
    Axn.unsafe.delayContext { ctx => MinimalRandom.unsafe2(initialSeed, ctx.refIdGen) }
}
