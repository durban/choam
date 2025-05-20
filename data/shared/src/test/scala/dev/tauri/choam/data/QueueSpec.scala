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

import core.{ Rxn, Axn }

final class QueueMsSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with QueueMsSpec[IO]
  with SpecThreadConfinedMcas

final class QueueWithRemoveSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with QueueWithRemoveSpec[IO]
  with SpecThreadConfinedMcas

final class QueueWithSizeSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with QueueWithSizeSpec[IO]
  with SpecThreadConfinedMcas

final class QueueGcHostileSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with QueueGcHostileSpec[IO]
  with SpecThreadConfinedMcas

trait QueueWithSizeSpec[F[_]] extends BaseQueueSpec[F] { this: McasImplSpec =>

  override type QueueType[A] = Queue.WithSize[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] = {
    Queue.fromList(Queue.unboundedWithSize[A])(as)
  }

  test("Queue size composed with other ops") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- assertResultF(q.size.run[F], 0)
      rxn = for {
        s0 <- q.size
        _ <- q.enqueue.provide("a")
        _ <- q.enqueue.provide("b")
        s1 <- q.size
        _ <- q.enqueue.provide("c")
        _ <- q.tryDeque
        s2 <- q.size
        _ <- q.tryDeque
        s3 <- q.size
      } yield (s0, s1, s2, s3)
      _ <- assertResultF(rxn.run[F], (0, 2, 2, 1))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some("c"))
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }
}

trait QueueWithRemoveSpec[F[_]] extends BaseQueueSpec[F] { this: McasImplSpec =>

  private[data] override type QueueType[A] = RemoveQueue[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    Queue.fromList(RemoveQueue.apply[A])(as)

  test("Queue.WithRemove#remove") {
    for {
      q <- newQueueFromList(List("a", "b", "c", "d", "e"))
      _ <- assertResultF(q.remove[F]("a"), true)
      _ <- assertResultF(q.remove[F]("a"), false)
      _ <- assertResultF(q.tryDeque.run[F], Some("b"))
      _ <- assertResultF(q.remove[F]("c"), true)
      _ <- assertResultF(q.remove[F]("d"), true)
      _ <- assertResultF(q.remove[F]("d"), false)
      _ <- assertResultF(q.tryDeque.run[F], Some("e"))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("Queue.WithRemove#remove with enq/deq") {
    for {
      q <- newQueueFromList(List("a", "b", "c", "d", "e"))
      _ <- assertResultF(q.remove[F]("a"), true)
      _ <- assertResultF(q.remove[F]("c"), true)
      _ <- assertResultF(q.remove[F]("d"), true)
      _ <- q.enqueue[F]("f")
      _ <- assertResultF(q.tryDeque.run[F], Some("b"))
      _ <- assertResultF(q.remove[F]("f"), true)
      _ <- assertResultF(q.remove[F]("f"), false)
      _ <- assertResultF(q.tryDeque.run[F], Some("e"))
      _ <- q.enqueue[F]("x")
      _ <- assertResultF(q.tryDeque.run[F], Some("x"))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("Queue.WithRemove#remove first occurrence") {
    for {
      q <- newQueueFromList(List("b", "a", "c", "a"))
      _ <- assertResultF(q.remove[F]("a"), true)
      _ <- assertResultF(q.tryDeque.run[F], Some("b"))
      _ <- assertResultF(q.tryDeque.run[F], Some("c"))
      _ <- assertResultF(q.tryDeque.run[F], Some("a"))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("Queue.WithRemove enq then remove") {
    for {
      q <- newQueueFromList(List("a", "b", "c"))
      _ <- assertResultF(q.remove[F]("x"), false)
      _ <- assertResultF(q.tryDeque.run[F], Some("a"))
      _ <- q.enqueue[F]("x")
      _ <- assertResultF(q.tryDeque.run[F], Some("b"))
      _ <- assertResultF(q.remove[F]("x"), true)
      _ <- assertResultF(q.tryDeque.run[F], Some("c"))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("Null element") {
    for {
      q <- newQueueFromList(List("a", "b", "c"))
      _ <- q.enqueue[F](null : String)
      _ <- assertResultF(q.drainOnce, List("a", "b", "c", null))
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- q.enqueue[F](null : String)
      _ <- q.enqueue[F]("a")
      _ <- q.enqueue[F](null : String)
      _ <- q.enqueue[F]("b")
      _ <- q.enqueue[F]("a")
      // null, a, null, b, a
      _ <- q.remove[F]("a")
      // null, null, b, a
      _ <- assertResultF(q.tryDeque.run[F], Some(null))
      // null, b, a
      _ <- q.remove[F](null)
      // b, a
      _ <- assertResultF(q.tryDeque.run[F], Some("b"))
      _ <- assertResultF(q.tryDeque.run[F], Some("a"))
    } yield ()
  }

  test("enqueueWithRemover") {
    for {
      q <- newQueueFromList(List.empty[Int])
      r1 <- q.enqueueWithRemover[F](1)
      r2 <- q.enqueueWithRemover[F](2)
      r3 <- q.enqueueWithRemover[F](3)
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- r1.run[F] // does nothing
      _ <- r2.run[F] // removes 2
      _ <- r2.run[F] // does nothing
      _ <- assertResultF(q.tryDeque.run[F], Some(3))
      _ <- r3.run[F] // does nothing
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- r3.run[F] // does nothing
      _ <- assertResultF(q.tryDeque.run[F], None)
      r42 <- q.enqueueWithRemover[F](42)
      _ <- r42.run[F] // removes 42
      _ <- q.enqueue[F](33)
      _ <- assertResultF(q.tryDeque.run[F], Some(33))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("Concurrent removals") {
    val N = 1024
    val P = 128
    val S = List("a", "b", "c", "d", "e", "f", "g", "h")
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      q <- newQueueFromList(S)
      finderRemovers = S.map { item =>
        q.remove.provide(item).void
      }
      directRemovers <- (1 to N).toList.parTraverseN(P) { idx =>
        q.enqueueWithRemover[F](idx.toString).map { remover =>
          // we only want to remove even indices:
          if ((idx % 2) == 0) remover
          else Rxn.unit
        }
      }
      _ <- F.both(
        finderRemovers.parTraverseN(P >>> 1) { r => r.run[F] },
        directRemovers.parTraverseN(P >>> 1) { r => r.run[F] },
      )
      contents <- q.drainOnce
      _ <- assertResultF(q.tryDeque.run[F], None)
      // the odd indices should've remained:
      expected = (1 to N).collect { case i if (i % 2) != 0 => i.toString }.toList
      _ <- assertEqualsF(contents.toSet, expected.toSet)
      _ <- assertEqualsF(contents.length, expected.length)
    } yield ()
  }
}

trait QueueMsSpec[F[_]] extends BaseQueueSpec[F] { this: McasImplSpec =>

  private[data] override type QueueType[A] = MsQueue[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    Queue.fromList(MsQueue.unpadded[A])(as)

  test("MS-queue lagging tail") {
    for {
      q <- newQueueFromList[Int](Nil)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- q.enqueue[F](1)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- q.enqueue[F](2)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- q.enqueue[F](3)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], Some(2))
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], Some(3))
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- (q.enqueue.provide(1) *> q.enqueue.provide(2) *> q.enqueue.provide(3) *> q.enqueue.provide(4)).run[F]
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(
        (q.tryDeque * q.tryDeque * q.tryDeque * q.tryDeque).run[F],
        (((Some(1), Some(2)), Some(3)), Some(4)),
      )
    } yield ()
  }
}

trait QueueGcHostileSpec[F[_]] extends BaseQueueSpec[F] { this: McasImplSpec =>

  private[data] override type QueueType[A] = GcHostileMsQueue[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    GcHostileMsQueue.fromList(as)
}

trait BaseQueueSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  private[data] type QueueType[A] <: Queue[A]

  protected def newQueueFromList[A](as: List[A]): F[this.QueueType[A]]

  test("Queue should include the elements passed to its constructor") {
    for {
      q1 <- newQueueFromList[Int](Nil)
      _ <- assertResultF(q1.tryDeque.run[F], None)
      q2 <- newQueueFromList[Int](1 :: 2 :: 3 :: Nil)
      _ <- assertResultF(q2.drainOnce, List(1, 2, 3))
      _ <- assertResultF(q2.tryDeque.run[F], None)
    } yield ()
  }

  test("Queue transfer") {
    for {
      q1 <- newQueueFromList(1 :: 2 :: 3 :: Nil)
      q2 <- newQueueFromList(List.empty[Int])
      r = q1.tryDeque.map(_.getOrElse(0)) >>> q2.enqueue
      _ <- r.run[F]
      _ <- r.run[F]
      _ <- assertResultF(q1.drainOnce, List(3))
      _ <- assertResultF(q1.tryDeque.run[F], None)
      _ <- assertResultF(q2.drainOnce, List(1, 2))
      _ <- assertResultF(q2.tryDeque.run[F], None)
    } yield ()
  }

  test("Queue should work correctly") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- assertResultF(q.tryDeque.run[F], None)

      _ <- assertResultF(q.tryDeque.run, None)
      _ <- assertResultF(q.tryDeque.run[F], None)

      _ <- q.enqueue("a")
      _ <- assertResultF(q.tryDeque.run, Some("a"))
      _ <- assertResultF(q.tryDeque.run, None)

      _ <- q.enqueue("a")
      _ <- q.enqueue("b")
      _ <- q.enqueue("c")
      _ <- assertResultF(q.tryDeque.run, Some("a"))

      _ <- q.enqueue("x")
      _ <- assertResultF(q.drainOnce, List("b", "c", "x"))
      _ <- assertResultF(q.tryDeque.run, None)
    } yield ()
  }

  test("Queue multiple enq/deq in one Rxn") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- assertResultF(q.tryDeque.run[F], None)
      rxn = for {
        _ <- q.enqueue.provide("a")
        _ <- q.enqueue.provide("b")
        _ <- q.enqueue.provide("c")
        a <- q.tryDeque
        b <- q.tryDeque
        _ <- q.enqueue.provide("d")
        c <- q.tryDeque
        d <- q.tryDeque
      } yield (a, b, c, d)
      abcd <- rxn.run[F]
      _ <- assertEqualsF(abcd, (Some("a"), Some("b"), Some("c"), Some("d")))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("Queue parallel multiple enq/deq") {
    val TaskSize = 1024
    val RxnSize = 16
    val Parallelism = 256
    def enqTask(q: QueueType[Int], idx: Int): F[Unit] = {
      val shifted = idx << 8
      require(shifted >= idx) // ensure no overflow
      val items = (1 to RxnSize).toList.map(_ | shifted)
      val rxn: Axn[Unit] = items.traverse_ { item => q.enqueue.provide(item) }
      rF.run(rxn)
    }
    def deqTask(q: QueueType[Int]): F[Int] = {
      def goOnce(acc: List[Int]): Axn[List[Int]] = {
        if (acc.length == RxnSize) {
          Rxn.pure(acc.reverse)
        } else {
          q.tryDeque.flatMapF {
            case None =>
              Axn.unsafe.delay(assert(acc.isEmpty)).as(Nil)
            case Some(item) =>
              goOnce(item :: acc)
          }
        }
      }
      def go: F[List[Int]] = {
        goOnce(Nil).run[F].flatMap { lst =>
          if (lst.isEmpty) go
          else F.pure(lst)
        }
      }
      go.flatMap { block =>
        assertEqualsF(block.length, RxnSize) >> (
          assertEqualsF(block.map(_ >>> 8).toSet.size, 1)
        ) >> F.pure(block.head >>> 8)
      }
    }
    for {
      _ <- this.assumeF(this.mcasImpl.isThreadSafe)
      q <- newQueueFromList[Int](Nil)
      indices = (0 until TaskSize).toList
      results <- indices.parTraverseN(Parallelism) { idx =>
        F.both(F.cede *> enqTask(q, idx), F.cede *> deqTask(q)).map(_._2)
      }
      _ <- assertEqualsF(results.sorted, indices)
    } yield ()
  }
}
