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
package stream

import cats.effect.IO
import fs2.Chunk

final class PubSubSpec_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with PubSubSpec[IO]

trait PubSubSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with async.AsyncReactiveSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  commonTests("DropOldest", PubSub.OverflowStrategy.DropOldest(64))
  commonTests("DropNewest", PubSub.OverflowStrategy.DropNewest(64))
  commonTests("Backpressure", PubSub.OverflowStrategy.Backpressure(64))
  commonTests("Unbounded", PubSub.OverflowStrategy.Unbounded)

  private def commonTests(name: String, str: PubSub.OverflowStrategy): Unit = {

    test(s"$name - basics") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)).run[F], PubSub.Success)
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.publish(4).run[F], PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(5, 6)).run[F], PubSub.Success)
        _ <- hub.close.run[F]
        _ <- assertResultF(f1.joinWithNever, Vector(1, 2, 3, 4, 5, 6))
        _ <- assertResultF(f3.joinWithNever, Vector(2, 3, 4, 5, 6, 7))
      } yield ()
    }
  }
}
