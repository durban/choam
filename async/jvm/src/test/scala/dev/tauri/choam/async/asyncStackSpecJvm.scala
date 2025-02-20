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
package async

import cats.effect.IO

final class AsyncStackSpec_Treiber_Emcas_IO
  extends BaseSpecTickedIO
  with SpecEmcas
  with AsyncStackSpec_Treiber[IO]

final class AsyncStackSpec_Treiber_Emcas_ZIO
  extends BaseSpecTickedZIO
  with SpecEmcas
  with AsyncStackSpec_Treiber[zio.Task]

final class AsyncStackSpec_Treiber_SpinLockMcas_IO
  extends BaseSpecTickedIO
  with SpecSpinLockMcas
  with AsyncStackSpec_Treiber[IO]

final class AsyncStackSpec_Treiber_SpinLockMcas_ZIO
  extends BaseSpecTickedZIO
  with SpecSpinLockMcas
  with AsyncStackSpec_Treiber[zio.Task]


final class AsyncStackSpec_Elimination_Emcas_IO
  extends BaseSpecTickedIO
  with SpecEmcas
  with AsyncStackSpec_Elimination[IO]

final class AsyncStackSpec_Elimination_Emcas_ZIO
  extends BaseSpecTickedZIO
  with SpecEmcas
  with AsyncStackSpec_Elimination[zio.Task]

final class AsyncStackSpec_Elimination_SpinLockMcas_IO
  extends BaseSpecTickedIO
  with SpecSpinLockMcas
  with AsyncStackSpec_Elimination[IO]

final class AsyncStackSpec_Elimination_SpinLockMcas_ZIO
  extends BaseSpecTickedZIO
  with SpecSpinLockMcas
  with AsyncStackSpec_Elimination[zio.Task]
