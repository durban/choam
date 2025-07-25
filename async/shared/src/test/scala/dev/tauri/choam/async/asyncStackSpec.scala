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
package async

import cats.effect.IO

import core.AsyncReactive

final class AsyncStackSpec_Treiber_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with AsyncStackSpec_Treiber[IO]

final class AsyncStackSpec_Elimination_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with AsyncStackSpec_Elimination[IO]

trait AsyncStackSpec_Treiber[F[_]]
  extends AsyncStackSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected final override def newStack[G[_] : AsyncReactive, A]: G[AsyncStack[A]] =
    AsyncStack.apply[A].run[G]
}

trait AsyncStackSpec_Elimination[F[_]]
  extends AsyncStackSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected final override def newStack[G[_] : AsyncReactive, A]: G[AsyncStack[A]] =
    AsyncStack.eliminationStack[A].run[G]
}

trait AsyncStackSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected def newStack[G[_] : AsyncReactive, A]: G[AsyncStack[A]]

  test("pop on a non-empty stack should work like on Treiber stack") {
    for {
      s <- newStack[F, String]
      _ <- s.push("foo").run[F]
      _ <- s.push("bar").run[F]
      _ <- assertResultF(s.pop, "bar")
      _ <- assertResultF(s.pop, "foo")
    } yield ()
  }

  test("pop on a non-empty stack should work for concurrent pops") {
    for {
      s <- newStack[F, String]
      _ <- s.push("xyz").run[F]
      _ <- s.push("foo").run[F]
      _ <- s.push("bar").run[F]
      pop = s.pop
      f1 <- pop.start
      f2 <- pop.start
      p1 <- f1.joinWithNever
      p2 <- f2.joinWithNever
      _ <- assertEqualsF(Set(p1, p2), Set("foo", "bar"))
      _ <- assertResultF(pop, "xyz")
    } yield ()
  }

  test("pop on an empty stack should complete with the correponding push") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- this.tickAll
      _ <- s.push("foo").run[F]
      p1 <- f1.joinWithNever
      _ <- assertEqualsF(p1, "foo")
    } yield ()
  }

  test("pop on an empty stack should work with racing pushes") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- this.tickAll
      f2 <- s.pop.start
      _ <- this.tickAll
      _ <- s.push("foo").run[F]
      _ <- assertResultF(f1.joinWithNever, "foo")
      _ <- s.push("bar").run[F]
      _ <- assertResultF(f2.joinWithNever, "bar")
    } yield ()
  }

  test("pops should be served in a FIFO manner") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- this.tickAll
      f2 <- s.pop.start
      _ <- this.tickAll
      f3 <- s.pop.start
      _ <- this.tickAll
      _ <- s.push("a").run[F]
      _ <- this.tickAll
      _ <- s.push("b").run[F]
      _ <- this.tickAll
      _ <- s.push("c").run[F]
      _ <- this.tickAll
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("cancellation should not cause elements to be lost") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- this.tickAll
      f2 <- s.pop.start
      _ <- this.tickAll
      f3 <- s.pop.start
      _ <- this.tickAll
      _ <- f2.cancel
      _ <- s.push("a").run[F]
      _ <- this.tickAll
      _ <- s.push("b").run[F]
      _ <- this.tickAll
      _ <- s.push("c").run[F]
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f3.joinWithNever, "b")
      _ <- assertResultF(s.pop, "c")
    } yield ()
  }

  test("Multiple ops in one Rxn") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- this.tickAll
      f2 <- s.pop.start
      _ <- this.tickAll
      rxn = (s.push("a") * s.push("b") * s.push("c")) *> (
        s.tryPop
      )
      _ <- assertResultF(rxn.run[F], Some("c"))
      _ <- this.tickAll
      // since `rxn` awakes both fibers in its post-commit actions, their order is non-deterministic
      v1 <- f1.joinWithNever
      v2 <- f2.joinWithNever
      _ <- assertEqualsF(Set(v1, v2), Set("b", "a"))
    } yield ()
  }
}
