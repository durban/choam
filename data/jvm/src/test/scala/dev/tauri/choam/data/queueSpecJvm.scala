/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.IO

final class QueueSpec_SpinLockMCAS_IO
  extends BaseSpecIO
  with QueueSpec[IO]
  with SpecSpinLockMCAS

final class QueueSpec_SpinLockMCAS_ZIO
  extends BaseSpecZIO
  with QueueSpec[zio.Task]
  with SpecSpinLockMCAS

final class QueueSpec_EMCAS_IO
  extends BaseSpecIO
  with QueueSpec[IO]
  with SpecEMCAS

final class QueueSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with QueueSpec[zio.Task]
  with SpecEMCAS

final class QueueWithRemoveSpec_SpinLockMCAS_IO
  extends BaseSpecIO
  with QueueWithRemoveSpec[IO]
  with SpecSpinLockMCAS

final class QueueWithRemoveSpec_SpinLockMCAS_ZIO
  extends BaseSpecZIO
  with QueueWithRemoveSpec[zio.Task]
  with SpecSpinLockMCAS

final class QueueWithRemoveSpec_EMCAS_IO
  extends BaseSpecIO
  with QueueWithRemoveSpec[IO]
  with SpecEMCAS

final class QueueWithRemoveSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with QueueWithRemoveSpec[zio.Task]
  with SpecEMCAS
