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

final class PubSubSpecAsync_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with PubSubSpecAsync[IO]

trait PubSubSpecAsync[F[_]] extends PubSubSpec[F] { this: McasImplSpec =>

  protected[this] final override type H[A] = PubSub[A]

  protected[this] final override def newHub[A](str: PubSub.OverflowStrategy): F[PubSub[A]] =
    PubSub.async[A](str).run[F]

  test("Backpressure retry should work") {
    val repeat = this.platform match {
      case Jvm => 500
      case Js => 2
      case Native => 250
    }
    val t = for {
      hub <- newHub[Int](PubSub.OverflowStrategy.unbounded)
      fib1 <- hub.subscribe[F].compile.toVector.start
      fib2 <- hub.subscribe[F](PubSub.OverflowStrategy.backpressure(2)).compile.toVector.start
      _ <- F.cede
      _ <- waitForSubscribers(hub, 2)
      _ <- hub.publish(1)
      _ <- hub.publish(2)
      _ <- hub.publish(3)
      _ <- hub.publish(4)
      _ <- hub.close.run
      _ <- assertResultF(fib1.joinWithNever, Vector(1, 2, 3, 4))
      _ <- assertResultF(fib2.joinWithNever, Vector(1, 2, 3, 4))
    } yield ()
    t.replicateA_(repeat)
  }
}
