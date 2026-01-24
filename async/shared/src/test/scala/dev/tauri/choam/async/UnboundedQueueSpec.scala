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
package async

import cats.effect.IO

import core.AsyncReactive

final class UnboundedQueueSpec_Simple_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with UnboundedQueueImplSimple[IO]

final class UnboundedQueueSpec_WithSize_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with UnboundedQueueImplWithSize[IO]

trait UnboundedQueueImplSimple[F[_]] extends UnboundedQueueSpec[F] { this: McasImplSpec & TestContextSpec[F] =>
  final override type Q[G[_], A] = AsyncQueue[A]
  protected final override def newQueue[G[_] : AsyncReactive, A] =
    AsyncQueue.unbounded[A].run[G]
}

trait UnboundedQueueImplWithSize[F[_]] extends UnboundedQueueSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  final override type Q[G[_], A] = AsyncQueue.WithSize[A]

  protected final override def newQueue[G[_] : AsyncReactive, A] =
    AsyncQueue.unboundedWithSize[A].run[G]

  test("UnboundedQueue.WithSize#asCats") {
    for {
      q <- newQueue[F, String]
      cq = q.asCats
      _ <- assertResultF(cq.size, 0)
      f <- cq.take.start
      _ <- this.tickAll
      _ <- q.put[F]("a")
      _ <- assertResultF(f.joinWithNever, "a")
      _ <- assertResultF(cq.size, 0)
      _ <- assertResultF(cq.tryTake, None)
      f <- q.take.start
      _ <- cq.offer("b")
      _ <- assertResultF(f.joinWithNever, "b")
      _ <- assertResultF(cq.size, 0)
      _ <- assertResultF(cq.tryOffer("c"), true)
      _ <- assertResultF(cq.size, 1)
      _ <- assertResultF(q.poll.run[F], Some("c"))
      _ <- assertResultF(cq.size, 0)
    } yield ()
  }
}

trait UnboundedQueueSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  type Q[G[_], A] <: AsyncQueue[A]

  protected def newQueue[G[_] : AsyncReactive, A]: G[Q[G, A]]

  test("UnboundedQueue non-empty take") {
    for {
      s <- newQueue[F, String]
      _ <- s.put[F]("a")
      _ <- s.put[F]("b")
      _ <- s.put[F]("c")
      _ <- assertResultF(s.peek.run, Some("a"))
      _ <- assertResultF(s.take, "a")
      _ <- assertResultF(s.peek.run, Some("b"))
      _ <- assertResultF(s.take, "b")
      _ <- assertResultF(s.peek.run, Some("c"))
      _ <- assertResultF(s.take, "c")
      _ <- assertResultF(s.peek.run, None)
    } yield ()
  }

  test("UnboundedQueue empty take") {
    for {
      s <- newQueue[F, String]
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      f3 <- s.take.start
      _ <- this.tickAll
      _ <- s.put[F]("a")
      _ <- this.tickAll
      _ <- s.put[F]("b")
      _ <- this.tickAll
      _ <- s.put[F]("c")
      _ <- this.tickAll
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("UnboundedQueue more enq in one Rxn") {
    for {
      s <- newQueue[F, String]
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      f3 <- s.take.start
      _ <- this.tickAll
      rxn = s.add("a") *> s.add("b") *> s.add("c") *> s.peek
      _ <- assertResultF(rxn.run[F], Some("a"))
      // since `rxn` awakes all fibers in its post-commit actions, their order is non-deterministic:
      v1 <- f1.joinWithNever
      v2 <- f2.joinWithNever
      v3 <- f3.joinWithNever
      _ <- assertEqualsF(Set(v1, v2, v3), Set("a", "b", "c"))
    } yield ()
  }

  test("UnboundedQueue enq and deq in one Rxn") {
    for {
      s <- newQueue[F, String]
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      rxn = (s.add("a") * s.add("b") * s.add("c")) *> (
        (s.peek * s.poll)
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, (Some("a"), Some("a")))
      // since `rxn` awakes all fibers in its post-commit actions, their order is non-deterministic:
      v1 <- f1.joinWithNever
      v2 <- f2.joinWithNever
      _ <- assertEqualsF(Set(v1, v2), Set("b", "c"))
    } yield ()
  }
}
