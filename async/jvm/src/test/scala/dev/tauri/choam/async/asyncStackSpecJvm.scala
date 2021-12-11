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

// TODO: doesn't work with NaiveKCAS,
// TODO: because RemoveQueue uses `null` as sentinel

final class AsyncStackSpec_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEMCAS
  with AsyncStackSpec[IO]

final class AsyncStackSpec_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEMCAS
  with AsyncStackSpec[zio.Task]
