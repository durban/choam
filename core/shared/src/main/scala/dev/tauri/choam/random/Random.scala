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

import cats.effect.std.{ Random => CRandom, SecureRandom }

object Random {

  final def fastRandom: Axn[CRandom[Axn]] =
    Rxn.unsafe.delay { _ => RxnThreadLocalRandom.unsafe() }

  // TODO: blocking; either fix, or remove
  private[choam] final def secureRandom: Axn[SecureRandom[Axn]] =
    Rxn.unsafe.delay { _ => RxnSecureRandom.unsafe() }

  final def deterministicRandom(initialSeed: Long): Axn[SplittableRandom[Axn]] =
    DeterministicRandom(initialSeed)

  // TODO: do we need this?
  private[choam] def minimalRandom1(initialSeed: Long): Axn[CRandom[Axn]] =
    Rxn.unsafe.delay { _ => MinimalRandom.unsafe1(initialSeed) }

  // TODO: do we need this?
  private[choam] def minimalRandom2(initialSeed: Long): Axn[CRandom[Axn]] =
    Rxn.unsafe.delay { _ => MinimalRandom.unsafe2(initialSeed) }
}
