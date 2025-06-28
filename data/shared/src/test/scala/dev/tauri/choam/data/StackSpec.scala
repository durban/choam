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
package data

import cats.effect.IO

import core.Ref

final class StackSpec_Treiber_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with StackSpecTreiber[IO]

final class StackSpec_Elimination2_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with StackSpecElimination2[IO]

trait StackSpecTreiber[F[_]] extends StackSpec[F] { this: McasImplSpec =>
  final override def newStack[A](as: A*): F[Stack[A]] = {
    TreiberStack.fromList(as.toList, Ref.AllocationStrategy.Default)
  }
}

trait StackSpecElimination2[F[_]] extends StackSpec[F] { this: McasImplSpec =>
  final override def newStack[A](as: A*): F[Stack[A]] = {
    Stack.fromList[F, A](Stack.eliminationStack[A])(as.toList)
  }
}

trait StackSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  def newStack[A](as: A*): F[Stack[A]]

  test("Stack push/pop") {
    for {
      s <- newStack[String]()
      _ <- s.push.run[F]("a")
      _ <- s.push.run[F]("b")
      _ <- s.push.run[F]("c")
      _ <- assertResultF(s.tryPop.run[F], Some("c"))
      _ <- assertResultF(s.tryPop.run[F], Some("b"))
      _ <- assertResultF(s.tryPop.run[F], Some("a"))
      _ <- assertResultF(s.tryPop.run[F], None)
    } yield ()
  }

  test("Stack multiple ops in one Rxn") {
    for {
      s <- newStack[String]()
      rxn = (s.push.provide("a") * s.push.provide("b")) *> (
        s.tryPop
      )
      _ <- assertResultF(rxn.run[F], Some("b"))
      _ <- assertResultF(s.tryPop.run[F], Some("a"))
      _ <- assertResultF(s.tryPop.run[F], None)
    } yield ()
  }

  test("Stack should include the elements passed to its constructor") {
    for {
      s1 <- newStack[Int]()
      _ <- assertResultF(Stack.popAll[F, Int](s1), Nil)
      s2 <- newStack(1, 2, 3)
      _ <- assertResultF(Stack.popAll[F, Int](s2), List(3, 2, 1))
    } yield ()
  }
}
