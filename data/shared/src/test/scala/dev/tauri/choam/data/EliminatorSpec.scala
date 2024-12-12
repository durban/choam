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

import cats.effect.IO

import core.Eliminator

final class EliminatorSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with EliminatorSpec[IO]

trait EliminatorSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  final class EliminationStackForTesting[A] private (
    underlying: TreiberStack[A],
    eliminator: Eliminator[A, Unit, Any, Option[A]],
  ) extends Stack[A] {

    override def push: Rxn[A, Unit] =
      eliminator.leftOp

    override def tryPop: Axn[Option[A]] =
      eliminator.rightOp

    override def size: Axn[Int] =
      underlying.size
  }

  final object EliminationStackForTesting {
    def apply[A]: Axn[EliminationStackForTesting[A]] = {
      TreiberStack[A].flatMapF { underlying =>
        Eliminator[A, Unit, Any, Option[A]](
          underlying.push,
          Some(_),
          underlying.tryPop,
          _ => (),
        ).map { eliminator =>
          new EliminationStackForTesting(underlying, eliminator)
        }
      }
    }
  }

  test("Eliminator.apply") {
    for {
      e <- Eliminator[String, String, String, String](
        Rxn.lift(s => s + "x"),
        s => s,
        Rxn.lift(s => s + "y"),
        s => s,
      ).run[F]
      _ <- assertResultF(e.leftOp[F]("a"), "ax")
      _ <- assertResultF(e.rightOp[F]("b"), "by")
    } yield ()
  }

  test("EliminationStackForTesting (basic)") {
    for {
      s <- EliminationStackForTesting[Int].run[F]
      _ <- assertResultF(s.tryPop.run[F], None)
      _ <- s.push[F](1)
      _ <- (s.push.provide(2) *> s.push.provide(3)).run[F]
      _ <- assertResultF(s.tryPop.run[F], Some(3))
      _ <- assertResultF((s.tryPop * s.tryPop).run[F], (Some(2), Some(1)))
      _ <- assertResultF(s.tryPop.run[F], None)
    } yield ()
  }
}