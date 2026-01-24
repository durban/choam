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

import cats.effect.SyncIO

final class SetSpec_Hash_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with SetSpecHash[SyncIO]

final class SetSpec_Ordered_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with SetSpecOrdered[SyncIO]

final class SetSpec_Hash_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with SetSpecHash[SyncIO]

final class SetSpec_Ordered_SpinLockMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecSpinLockMcas
  with SetSpecOrdered[SyncIO]
