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
package data

import cats.kernel.Hash
import cats.effect.SyncIO

final class MapSpec_Simple_SpinLockMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMCAS
  with MapSpecSimple[SyncIO]

final class MapSpec_Simple_EMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecEMCAS
  with MapSpecSimple[SyncIO]

final class MapSpec_Ttrie_SpinLockMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMCAS
  with MapSpecTtrie[SyncIO]

final class MapSpec_Ttrie_EMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecEMCAS
  with MapSpecTtrie[SyncIO]

final class MapSpec_Ttrie_ThreadConfinedMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMCAS
  with MapSpecTtrie[SyncIO]

trait MapSpecTtrie[F[_]] extends MapSpec[F] { this: KCASImplSpec =>

  override type MyMap[K, V] = Map[K, V]

  def mkEmptyMap[K: Hash, V]: F[Map[K, V]] =
    Map.ttrie[K, V].run[F]
}
