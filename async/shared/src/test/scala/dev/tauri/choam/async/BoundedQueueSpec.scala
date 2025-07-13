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

import scala.util.Try

import cats.effect.IO

import data.Queue.DrainOnceSyntax

final class BoundedQueueSpecLinked_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with BoundedQueueSpecLinked[IO]

final class BoundedQueueSpecArray_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with BoundedQueueSpecArray[IO]

trait BoundedQueueSpecLinked[F[_]]
  extends BoundedQueueSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  def newQueue[A](bound: Int): F[AsyncQueue.SourceSinkWithSize[A]] =
    BoundedQueueImpl.linked[A](bound).run[F].widen
}

trait BoundedQueueSpecArray[F[_]]
  extends BoundedQueueSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  def newQueue[A](bound: Int): F[AsyncQueue.SourceSinkWithSize[A]] =
    BoundedQueueImpl.array[A](bound).run[F].widen
}

trait BoundedQueueSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  def newQueue[A](bound: Int): F[AsyncQueue.SourceSinkWithSize[A]]

  test("BoundedQueue non-empty take") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- s.put("a")
      _ <- s.put("b")
      _ <- s.put("c")
      _ <- assertResultF(s.take, "a")
      _ <- assertResultF(s.take, "b")
      _ <- assertResultF(s.take, "c")
    } yield ()
  }

  test("BoundedQueue non-empty poll") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- s.put("a")
      _ <- s.put("b")
      _ <- s.put("c")
      _ <- assertResultF(s.poll.run[F], Some("a"))
      _ <- assertResultF(s.poll.run[F], Some("b"))
      _ <- assertResultF(s.poll.run[F], Some("c"))
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }

  test("BoundedQueue empty take") {
    for {
      s <- newQueue[String](bound = 4)
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      f3 <- s.take.start
      _ <- this.tickAll
      _ <- s.put("a")
      _ <- this.tickAll
      _ <- s.put("b")
      _ <- this.tickAll
      _ <- s.put("c")
      _ <- this.tickAll
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("BoundedQueue empty poll") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.poll.run[F], None)
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }

  test("BoundedQueue offer") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.offer("a").run[F], true)
      _ <- assertResultF(s.offer("b").run[F], true)
      _ <- assertResultF(s.offer("c").run[F], true)
      _ <- assertResultF(s.poll.run[F], Some("a"))
      _ <- assertResultF(s.offer("d").run[F], true)
      _ <- assertResultF(s.offer("e").run[F], true)
      _ <- assertResultF(s.offer("x").run[F], false)
      _ <- assertResultF(s.drainOnce, List("b", "c", "d", "e"))
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }

  test("BoundedQueue offer with waiters") {
    for {
      s <- newQueue[String](bound = 4)
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      _ <- assertResultF(s.offer("a").run[F], true)
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(s.offer("b").run[F], true)
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(s.offer("c").run[F], true)
      _ <- assertResultF(s.poll.run[F], Some("c"))
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }

  test("BoundedQueue multiple offer in a Rxn") {
    for {
      s <- newQueue[String](bound = 4)
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      f3 <- s.take.start
      _ <- this.tickAll
      rxn = s.offer("a") * s.offer("b") * s.offer("c")
      _ <- rxn.run[F]
      // since `rxn` awakes all fibers in its post-commit actions, their order is non-deterministic:
      v1 <- f1.joinWithNever
      v2 <- f2.joinWithNever
      v3 <- f3.joinWithNever
      _ <- assertEqualsF(Set(v1, v2, v3), Set("a", "b", "c"))
    } yield ()
  }

  test("BoundedQueue full put / take") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.size.run[F], 0)
      _ <- s.put("a")
      _ <- assertResultF(s.size.run[F], 1)
      _ <- s.put("b")
      _ <- assertResultF(s.size.run[F], 2)
      _ <- s.put("c")
      _ <- assertResultF(s.size.run[F], 3)
      _ <- s.put("d")
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.offer("x").run[F], false)
      _ <- assertResultF(s.size.run[F], 4)
      f1 <- s.put("e").start
      _ <- this.tickAll
      f2 <- s.put("f").start
      _ <- this.tickAll
      f3 <- s.put("g").start
      _ <- this.tickAll
      _ <- assertResultF(s.take, "a")
      _ <- f1.joinWithNever
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.take, "b")
      _ <- f2.joinWithNever
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.take, "c")
      _ <- f3.joinWithNever
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.take, "d")
      _ <- assertResultF(s.size.run[F], 3)
      _ <- assertResultF(s.take, "e")
      _ <- assertResultF(s.size.run[F], 2)
      _ <- assertResultF(s.take, "f")
      _ <- assertResultF(s.size.run[F], 1)
      _ <- assertResultF(s.take, "g")
      _ <- assertResultF(s.size.run[F], 0)
    } yield ()
  }

  test("BoundedQueue full put / poll") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.size.run[F], 0)
      _ <- s.put("a")
      _ <- assertResultF(s.size.run[F], 1)
      _ <- s.put("b")
      _ <- assertResultF(s.size.run[F], 2)
      _ <- s.put("c")
      _ <- assertResultF(s.size.run[F], 3)
      _ <- s.put("d")
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.offer("x").run[F], false)
      _ <- assertResultF(s.size.run[F], 4)
      f1 <- s.put("e").start
      _ <- this.tickAll
      f2 <- s.put("f").start
      _ <- this.tickAll
      f3 <- s.put("g").start
      _ <- this.tickAll
      _ <- assertResultF(s.poll.run[F], Some("a"))
      _ <- f1.joinWithNever
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.poll.run[F], Some("b"))
      _ <- f2.joinWithNever
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.poll.run[F], Some("c"))
      _ <- f3.joinWithNever
      _ <- assertResultF(s.size.run[F], 4)
      _ <- assertResultF(s.poll.run[F], Some("d"))
      _ <- assertResultF(s.size.run[F], 3)
      _ <- assertResultF(s.poll.run[F], Some("e"))
      _ <- assertResultF(s.size.run[F], 2)
      _ <- assertResultF(s.poll.run[F], Some("f"))
      _ <- assertResultF(s.size.run[F], 1)
      _ <- assertResultF(s.poll.run[F], Some("g"))
      _ <- assertResultF(s.size.run[F], 0)
      _ <- assertResultF(s.poll.run[F], None)
    } yield ()
  }

  test("BoundedQueue small bound") {
    for {
      s <- newQueue[String](bound = 1)
      _ <- s.put("a")
      _ <- assertResultF(s.offer("x").run[F], false)
      fib <- s.put("b").start
      _ <- this.tickAll
      _ <- assertResultF(s.take, "a")
      _ <- fib.joinWithNever
      _ <- assertResultF(s.take, "b")
    } yield ()
  }

  test("BoundedQueue illegal bound") {
    assert(Try { newQueue[String](0) }.isFailure)
    assert(Try { newQueue[String](-1) }.isFailure)
    assert(Try { newQueue[String](-99) }.isFailure)
  }

  test("BoundedQueue canceled getter") {
    for {
      s <- newQueue[String](bound = 4)
      f1 <- s.take.start
      _ <- this.tickAll
      f2 <- s.take.start
      _ <- this.tickAll
      f3 <- s.take.start
      _ <- this.tickAll
      _ <- f1.cancel
      _ <- this.tickAll
      _ <- s.put("a")
      _ <- this.tickAll
      _ <- s.put("b")
      _ <- this.tickAll
      _ <- s.put("c")
      _ <- this.tickAll
      _ <- assertResultF(f2.joinWithNever, "a")
      _ <- assertResultF(f3.joinWithNever, "b")
      _ <- assertResultF(s.take, "c")
    } yield ()
  }

  test("BoundedQueue canceled setter") {
    for {
      s <- newQueue[String](bound = 1)
      _ <- assertResultF(s.size.run[F], 0)
      _ <- s.put("a")
      _ <- assertResultF(s.size.run[F], 1)
      _ <- assertResultF(s.offer("x").run[F], false)
      f1 <- s.put("b").start
      _ <- this.tickAll
      f2 <- s.put("c").start
      _ <- this.tickAll
      f3 <- s.put("d").start
      _ <- this.tickAll
      _ <- f1.cancel
      _ <- this.tickAll
      _ <- assertResultF(s.size.run[F], 1)
      _ <- assertResultF(s.take, "a")
      _ <- f2.joinWithNever
      _ <- assertResultF(s.take, "c")
      _ <- f3.joinWithNever
      _ <- assertResultF(s.poll.run[F], Some("d"))
      _ <- assertResultF(s.size.run[F], 0)
    } yield ()
  }

  test("BoundedQueue#toCats") {
    for {
      bq <- newQueue[String](bound = 2)
      q = bq.toCats
      _ <- assertResultF(q.size, 0)
      _ <- q.offer("a")
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.tryOffer("b"), true)
      _ <- assertResultF(q.size, 2)
      _ <- assertResultF(q.tryOffer("x"), false)
      _ <- assertResultF(q.size, 2)
      _ <- assertResultF(q.take, "a")
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.take, "b")
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryTake, None)
      _ <- assertResultF(q.size, 0)
    } yield ()
  }

  test("currentSize should be correct even if changed in the same Rxn") {
    for {
      q <- newQueue[String](bound = 8)
      _ <- assertResultF(q.size.run[F], 0)
      rxn = (
        (q.offer("a") *> q.size).flatMap { s1 =>
          (q.offer("b") *> q.size).flatMap { s2 =>
            (q.poll *> q.size).map { s3 =>
              (s1, s2, s3)
            }
          }
        }
      )
      _ <- assertResultF(rxn.run[F], (1, 2, 1))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, "b")
      _ <- q.put("x")
      _ <- assertResultF(rxn.run[F], (2, 3, 2))
      _ <- assertResultF(q.take, "a")
      _ <- assertResultF(q.take, "b")
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }
}
