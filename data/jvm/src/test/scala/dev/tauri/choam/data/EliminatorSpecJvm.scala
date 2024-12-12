/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.syntax.all._
import cats.effect.IO
import cats.effect.instances.spawn.parallelForGenSpawn

final class EliminatorSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with EliminatorSpecJvm[zio.Task]

final class EliminatorSpecJvm_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with EliminatorSpecJvm[IO]

trait EliminatorSpecJvm[F[_]] extends EliminatorSpec[F] { this: McasImplSpec =>

  test("EliminationStackForTesting (elimination)") {
    val k = 4
    val t = for {
      _ <- F.cede
      s <- EliminationStackForTesting[Int].run[F]
      _ <- s.push[F](0)
      res <- F.both(
        List.fill(k)(s.tryPop.run[F]).parSequence,
        List.tabulate(k)(idx => s.push[F](idx + 1)).parSequence_,
      )
      popped = res._1.collect { case Some(i) => i }
      remaining = (k + 1) - popped.size
      maybePopped2 <- s.tryPop.run[F].replicateA(remaining)
      popped2 = maybePopped2.collect { case Some(i) => i}
      _ <- assertEqualsF(popped2.size, maybePopped2.size)
      set = (popped ++ popped2).toSet
      _ <- assertEqualsF(set.size, popped.size + popped2.size)
      _ <- assertEqualsF(set, (0 to k).toSet)
    } yield ()
    t.replicateA_(50000)
  }
}
