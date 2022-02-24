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
package async

import cats.effect.IO

final class UnboundedQueueSpec_Simple_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEmcas
  with UnboundedQueueImplSimple[IO]

final class UnboundedQueueSpec_Simple_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEmcas
  with UnboundedQueueImplSimple[zio.Task]

final class UnboundedQueueSpec_WithSize_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEmcas
  with UnboundedQueueImplWithSize[IO]

final class UnboundedQueueSpec_WithSize_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEmcas
  with UnboundedQueueImplWithSize[zio.Task]
