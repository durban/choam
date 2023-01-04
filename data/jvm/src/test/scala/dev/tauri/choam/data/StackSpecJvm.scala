/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

final class StackSpec_Treiber_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with StackSpecTreiberJvm[IO]

final class StackSpec_Treiber_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with StackSpecTreiberJvm[IO]

final class StackSpec_Elimination_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with StackSpecEliminationJvm[IO]

final class StackSpec_Elimination_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with StackSpecEliminationJvm[IO]

trait StackSpecTreiberJvm[F[_]]
  extends StackSpecTreiber[F]
  with  StackSpecJvm[F] { this: McasImplSpec =>
}

trait StackSpecEliminationJvm[F[_]]
  extends StackSpecElimination[F]
  with  StackSpecJvm[F] { this: McasImplSpec =>
}

trait StackSpecJvm[F[_]] { this: StackSpec[F] with McasImplSpec =>

  test("Multiple producers/consumers") {
    val N = 4
    val tsk = for {
      s <- this.newStack[String]()
      _ <- s.push[F]("a")
      _ <- s.push[F]("b")
      _ <- s.push[F]("c")
      _ <- s.push[F]("d")
      poppers <- F.parReplicateAN(Int.MaxValue)(replicas = N, ma = s.tryPop.run[F]).start
      pushers <- F.parReplicateAN(Int.MaxValue)(replicas = N, ma = s.push[F]("x")).start
      popRes <- poppers.joinWithNever
      _ <- pushers.joinWithNever
      remaining <- {
        def drain(acc: List[String]): F[List[String]] = {
          s.tryPop.run[F].flatMap {
            case Some(v) => drain(v :: acc)
            case None => F.pure(acc)
          }
        }
        drain(Nil)
      }
      _ <- assertEqualsF(
        (popRes.collect { case Some(v) => v } ++ remaining).toSet,
        (List("a", "b", "c", "d") ++ List.fill(N)("x")).toSet,
      )
    } yield ()
    tsk.replicateA(64).void
  }
}
