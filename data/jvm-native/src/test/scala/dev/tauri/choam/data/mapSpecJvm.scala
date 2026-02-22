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
package data

import cats.kernel.{ Hash, Order }
import cats.effect.SyncIO

final class MapSpec_SimpleHash_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with MapSpecSimpleHash[SyncIO]

final class MapSpec_SimpleHash_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with MapSpecSimpleHash[SyncIO]

final class MapSpec_SimpleOrdered_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with MapSpecSimpleOrdered[SyncIO]

final class MapSpec_SimpleOrdered_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with MapSpecSimpleOrdered[SyncIO]

final class MapSpec_TtrieHash_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with MapSpecTtrieHash[SyncIO]

final class MapSpec_TtrieOrder_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with MapSpecTtrieOrder[SyncIO]

final class MapSpec_TtrieHash_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with MapSpecTtrieHash[SyncIO]

final class MapSpec_TtrieOrder_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with MapSpecTtrieOrder[SyncIO]

final class MapSpec_TtrieHash_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with MapSpecTtrieHash[SyncIO]

final class MapSpec_TtrieOrder_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with MapSpecTtrieOrder[SyncIO]

final class MapSpec_Imapped_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with MapSpecImapped[SyncIO]

final class MapSpec_Imapped_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with MapSpecImapped[SyncIO]

final class MapSpec_Product_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with MapSpecProduct[SyncIO]

final class MapSpec_Product_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with MapSpecProduct[SyncIO]

trait MapSpecTtrieHash[F[_]] extends MapSpec[F] { this: McasImplSpec =>

  override type MyMap[K, V] = Map[K, V]

  def mkEmptyMap[K : Hash : Order, V]: F[Map[K, V]] =
    Ttrie.apply[K, V](AllocationStrategy.Default).run[F].widen
}

trait MapSpecTtrieOrder[F[_]] extends MapSpec[F] { this: McasImplSpec =>

  override type MyMap[K, V] = Map[K, V]

  def mkEmptyMap[K : Hash : Order, V]: F[Map[K, V]] =
    Ttrie.skipListBased[K, V](AllocationStrategy.Default).run[F].widen
}
