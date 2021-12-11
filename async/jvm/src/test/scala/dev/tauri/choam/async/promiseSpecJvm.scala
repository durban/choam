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
package async

import cats.effect.IO

final class PromiseSpec_SpinLockMCAS_IO_Real
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with PromiseSpec[IO]

final class PromiseSpec_SpinLockMCAS_IO_Ticked
  extends BaseSpecTickedIO
  with SpecSpinLockMCAS
  with PromiseSpecTicked[IO]

final class PromiseSpec_SpinLockMCAS_ZIO_Real
  extends BaseSpecZIO
  with SpecSpinLockMCAS
  with PromiseSpec[zio.Task]

final class PromiseSpec_SpinLockMCAS_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecSpinLockMCAS
  with PromiseSpecTicked[zio.Task]

final class PromiseSpec_EMCAS_IO_Real
  extends BaseSpecIO
  with SpecEMCAS
  with PromiseSpec[IO]

final class PromiseSpec_EMCAS_IO_Ticked
  extends BaseSpecTickedIO
  with SpecEMCAS
  with PromiseSpecTicked[IO]

final class PromiseSpec_EMCAS_ZIO_Real
  extends BaseSpecZIO
  with SpecEMCAS
  with PromiseSpec[zio.Task]

final class PromiseSpec_EMCAS_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecEMCAS
  with PromiseSpecTicked[zio.Task]
