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

final class AsyncFromSpec_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with AsyncFromSpec[IO]

trait AsyncFromSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  test("AsyncFrom around a Ref") {
    for {
      ref <- Ref[Option[Int]](None).run[F]
      af <- rF.waitList[Int](
        ref.get,
        ref.getAndSet.contramap[Int](Some(_)).void
      ).run[F]
      f1 <- af.get.start
      _ <- this.tickAll
      f2 <- af.get.start
      _ <- af.set[F](42)
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- af.set[F](21)
      _ <- assertResultF(f2.joinWithNever, 21)
    } yield ()
  }
}
