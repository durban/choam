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
package stream

import cats.effect.IO

final class PubSubSpecTickedSimple_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with PubSubSpecTickedSimple[IO]

trait PubSubSpecTickedSimple[F[_]] extends PubSubSpecTicked[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected[this] final override type H[A] = PubSub.Simple[A]

  protected[this] final override def newHub[A](str: PubSub.OverflowStrategy): F[PubSub.Simple[A]] =
    PubSub.simple[A](str).run[F]
}
