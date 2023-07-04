/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.std.{ Random => CRandom, SecureRandom, UUIDGen }

private[choam] object Random {

  private[choam] final def uuidGen[X](rng: OsRng): UUIDGen[Rxn[X, *]] =
    new RxnUuidGen[X](rng)

  private[choam] final def newFastRandom: CRandom[Axn] =
    new RxnThreadLocalRandom

  private[choam] final def newSecureRandom(rng: OsRng): SecureRandom[Axn] =
    new SecureRandomRxn(rng)

  @deprecated("Don't use secureRandomWrapper, because it may block", since = "0.4")
  private[choam] final def secureRandomWrapper: Axn[SecureRandom[Axn]] =
    Axn.unsafe.delay { SecureRandomWrapper.unsafe() }

  final def deterministicRandom(initialSeed: Long): Axn[SplittableRandom[Axn]] =
    DeterministicRandom(initialSeed)

  // TODO: do we need this?
  private[choam] def minimalRandom1(initialSeed: Long): Axn[CRandom[Axn]] =
    Axn.unsafe.delay { MinimalRandom.unsafe1(initialSeed) }

  // TODO: do we need this?
  private[choam] def minimalRandom2(initialSeed: Long): Axn[CRandom[Axn]] =
    Axn.unsafe.delay { MinimalRandom.unsafe2(initialSeed) }
}
