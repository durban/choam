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

final class CountDownLatchSpec_SpinLockMcas_ZIO_Real
  extends BaseSpecZIO
  with SpecSpinLockMcas
  with CountDownLatchSpec[zio.Task]

final class CountDownLatchSpec_SpinLockMcas_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecSpinLockMcas
  with CountDownLatchSpecTicked[zio.Task]

final class CountDownLatchSpec_Emcas_ZIO_Real
  extends BaseSpecZIO
  with SpecEmcas
  with CountDownLatchSpec[zio.Task]

final class CountDownLatchSpec_Emcas_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecEmcas
  with CountDownLatchSpecTicked[zio.Task]
