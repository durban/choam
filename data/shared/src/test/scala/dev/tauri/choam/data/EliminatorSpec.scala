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
package data

import cats.effect.IO

import core.{ Rxn, Eliminator }

final class EliminatorSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with EliminatorSpec[IO]

trait EliminatorSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("Eliminator.apply") {
    for {
      e <- Eliminator[String, String, String, String](
        s => Rxn.pure(s + "x"),
        s => s,
        s => Rxn.pure(s + "y"),
        s => s,
      ).run[F]
      _ <- assertResultF(e.leftOp("a").run[F], "ax")
      _ <- assertResultF(e.rightOp("b").run[F], "by")
    } yield ()
  }

  test("Eliminator.tagged") {
    for {
      e <- Eliminator.tagged[String, String, String, String](
        s => Rxn.pure(s + "x"),
        s => s,
        s => Rxn.pure(s + "y"),
        s => s,
      ).run[F]
      _ <- assertResultF(e.leftOp("a").run[F], Left("ax"))
      _ <- assertResultF(e.rightOp("b").run[F], Left("by"))
    } yield ()
  }

  test("EliminationStackForTesting (basic)") {
    for {
      s <- EliminationStackForTesting[Int].run[F]
      _ <- assertResultF(s.poll.run[F], None)
      _ <- s.push(1).run[F]
      _ <- (s.push(2) *> s.push(3)).run[F]
      _ <- assertResultF(s.poll.run[F], Some(3))
      _ <- assertResultF((s.poll * s.poll).run[F], (Some(2), Some(1)))
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }

  test("EliminationStack2 (basic)") {
    for {
      s <- EliminationStack[Int].run[F]
      _ <- assertResultF(s.poll.run[F], None)
      _ <- s.push(1).run[F]
      _ <- (s.push(2) *> s.push(3)).run[F]
      _ <- assertResultF(s.poll.run[F], Some(3))
      _ <- assertResultF((s.poll * s.poll).run[F], (Some(2), Some(1)))
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }
}
