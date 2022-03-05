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

import scala.util.Try

import cats.effect.IO

final class BoundedQueueSpecLinked_ThreadConfinedMCAS_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMCAS
  with BoundedQueueSpecLinked[IO]

final class BoundedQueueSpecArray_ThreadConfinedMCAS_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMCAS
  with BoundedQueueSpecArray[IO]

trait BoundedQueueSpecLinked[F[_]]
  extends BoundedQueueSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  def newQueue[A](bound: Int): F[BoundedQueue[F, A]] =
    BoundedQueue.linked[F, A](bound).run[F]
}

trait BoundedQueueSpecArray[F[_]]
  extends BoundedQueueSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  def newQueue[A](bound: Int): F[BoundedQueue[F, A]] =
    BoundedQueue.array[F, A](bound).run[F]
}

trait BoundedQueueSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  def newQueue[A](bound: Int): F[BoundedQueue[F, A]]

  test("BoundedQueue bound") {
    (1 to 1024).toList.traverse { b =>
      newQueue[String](bound = b).flatMap { q =>
        assertEqualsF(q.bound, b)
      }
    }
  }

  test("BoundedQueue non-empty deque") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- s.enqueue("a")
      _ <- s.enqueue("b")
      _ <- s.enqueue("c")
      _ <- assertResultF(s.deque, "a")
      _ <- assertResultF(s.deque, "b")
      _ <- assertResultF(s.deque, "c")
    } yield ()
  }

  test("BoundedQueue non-empty tryDeque") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- s.enqueue("a")
      _ <- s.enqueue("b")
      _ <- s.enqueue("c")
      _ <- assertResultF(s.tryDeque.run[F], Some("a"))
      _ <- assertResultF(s.tryDeque.run[F], Some("b"))
      _ <- assertResultF(s.tryDeque.run[F], Some("c"))
      _ <- assertResultF(s.tryDeque.run[F], None)
    } yield ()
  }

  test("BoundedQueue empty deque") {
    for {
      s <- newQueue[String](bound = 4)
      f1 <- s.deque.start
      _ <- this.tickAll
      f2 <- s.deque.start
      _ <- this.tickAll
      f3 <- s.deque.start
      _ <- this.tickAll
      _ <- s.enqueue("a")
      _ <- s.enqueue("b")
      _ <- s.enqueue("c")
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("BoundedQueue empty tryDeque") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.tryDeque.run[F], None)
      _ <- assertResultF(s.tryDeque.run[F], None)
    } yield ()
  }

  test("BoundedQueue tryEnqueue") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.tryEnqueue[F]("a"), true)
      _ <- assertResultF(s.tryEnqueue[F]("b"), true)
      _ <- assertResultF(s.tryEnqueue[F]("c"), true)
      _ <- assertResultF(s.tryDeque.run[F], Some("a"))
      _ <- assertResultF(s.tryEnqueue[F]("d"), true)
      _ <- assertResultF(s.tryEnqueue[F]("e"), true)
      _ <- assertResultF(s.tryEnqueue[F]("x"), false)
      _ <- assertResultF(s.drainOnce, List("b", "c", "d", "e"))
      _ <- assertResultF(s.tryDeque.run[F], None)
    } yield ()
  }

  test("BoundedQueue multiple enq in a Rxn") {
    for {
      s <- newQueue[String](bound = 4)
      f1 <- s.deque.start
      _ <- this.tickAll
      f2 <- s.deque.start
      _ <- this.tickAll
      f3 <- s.deque.start
      _ <- this.tickAll
      rxn = s.tryEnqueue.provide("a") * s.tryEnqueue.provide("b") * s.tryEnqueue.provide("c")
      _ <- rxn.run[F]
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("BoundedQueue full enqueue / deque") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.currentSize.run[F], 0)
      _ <- s.enqueue("a")
      _ <- assertResultF(s.currentSize.run[F], 1)
      _ <- s.enqueue("b")
      _ <- assertResultF(s.currentSize.run[F], 2)
      _ <- s.enqueue("c")
      _ <- assertResultF(s.currentSize.run[F], 3)
      _ <- s.enqueue("d")
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- assertResultF(s.tryEnqueue[F]("x"), false)
      _ <- assertResultF(s.currentSize.run[F], 4)
      f1 <- s.enqueue("e").start
      _ <- this.tickAll
      f2 <- s.enqueue("f").start
      _ <- this.tickAll
      f3 <- s.enqueue("g").start
      _ <- this.tickAll
      _ <- assertResultF(s.deque, "a")
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- f1.joinWithNever
      _ <- assertResultF(s.deque, "b")
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- f2.joinWithNever
      _ <- assertResultF(s.deque, "c")
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- f3.joinWithNever
      _ <- assertResultF(s.deque, "d")
      _ <- assertResultF(s.currentSize.run[F], 3)
      _ <- assertResultF(s.deque, "e")
      _ <- assertResultF(s.currentSize.run[F], 2)
      _ <- assertResultF(s.deque, "f")
      _ <- assertResultF(s.currentSize.run[F], 1)
      _ <- assertResultF(s.deque, "g")
      _ <- assertResultF(s.currentSize.run[F], 0)
    } yield ()
  }

  test("BoundedQueue full enqueue / tryDeque") {
    for {
      s <- newQueue[String](bound = 4)
      _ <- assertResultF(s.currentSize.run[F], 0)
      _ <- s.enqueue("a")
      _ <- assertResultF(s.currentSize.run[F], 1)
      _ <- s.enqueue("b")
      _ <- assertResultF(s.currentSize.run[F], 2)
      _ <- s.enqueue("c")
      _ <- assertResultF(s.currentSize.run[F], 3)
      _ <- s.enqueue("d")
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- assertResultF(s.tryEnqueue[F]("x"), false)
      _ <- assertResultF(s.currentSize.run[F], 4)
      f1 <- s.enqueue("e").start
      _ <- this.tickAll
      f2 <- s.enqueue("f").start
      _ <- this.tickAll
      f3 <- s.enqueue("g").start
      _ <- this.tickAll
      _ <- assertResultF(s.tryDeque.run[F], Some("a"))
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- f1.joinWithNever
      _ <- assertResultF(s.tryDeque.run[F], Some("b"))
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- f2.joinWithNever
      _ <- assertResultF(s.tryDeque.run[F], Some("c"))
      _ <- assertResultF(s.currentSize.run[F], 4)
      _ <- f3.joinWithNever
      _ <- assertResultF(s.tryDeque.run[F], Some("d"))
      _ <- assertResultF(s.currentSize.run[F], 3)
      _ <- assertResultF(s.tryDeque.run[F], Some("e"))
      _ <- assertResultF(s.currentSize.run[F], 2)
      _ <- assertResultF(s.tryDeque.run[F], Some("f"))
      _ <- assertResultF(s.currentSize.run[F], 1)
      _ <- assertResultF(s.tryDeque.run[F], Some("g"))
      _ <- assertResultF(s.currentSize.run[F], 0)
      _ <- assertResultF(s.tryDeque.run[F], None)
    } yield ()
  }

  test("BoundedQueue small bound") {
    for {
      s <- newQueue[String](bound = 1)
      _ <- s.enqueue("a")
      _ <- assertResultF(s.tryEnqueue[F]("x"), false)
      fib <- s.enqueue("b").start
      _ <- this.tickAll
      _ <- assertResultF(s.deque, "a")
      _ <- fib.joinWithNever
      _ <- assertResultF(s.deque, "b")
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
      f1 <- s.deque.start
      _ <- this.tickAll
      f2 <- s.deque.start
      _ <- this.tickAll
      f3 <- s.deque.start
      _ <- this.tickAll
      _ <- f1.cancel
      _ <- s.enqueue("a")
      _ <- s.enqueue("b")
      _ <- s.enqueue("c")
      _ <- assertResultF(f2.joinWithNever, "a")
      _ <- assertResultF(f3.joinWithNever, "b")
      _ <- assertResultF(s.deque, "c")
    } yield ()
  }

  test("BoundedQueue canceled setter") {
    for {
      s <- newQueue[String](bound = 1)
      _ <- assertResultF(s.currentSize.run[F], 0)
      _ <- s.enqueue("a")
      _ <- assertResultF(s.currentSize.run[F], 1)
      _ <- assertResultF(s.tryEnqueue[F]("x"), false)
      f1 <- s.enqueue("b").start
      _ <- this.tickAll
      f2 <- s.enqueue("c").start
      _ <- this.tickAll
      f3 <- s.enqueue("d").start
      _ <- this.tickAll
      _ <- f1.cancel
      _ <- this.tickAll
      _ <- assertResultF(s.currentSize.run[F], 1)
      _ <- assertResultF(s.deque, "a")
      _ <- f2.joinWithNever
      _ <- assertResultF(s.deque, "c")
      _ <- f3.joinWithNever
      _ <- assertResultF(s.tryDeque.run[F], Some("d"))
      _ <- assertResultF(s.currentSize.run[F], 0)
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
}
