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

import core.Rxn
import Queue.DrainOnceSyntax

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
        _ <- q.add("a")
        _ <- q.add("b")
        s1 <- q.size
        _ <- q.add("c")
        _ <- q.poll
        s2 <- q.size
        _ <- q.poll
        s3 <- q.size
      } yield (s0, s1, s2, s3)
      _ <- assertResultF(rxn.run[F], (0, 2, 2, 1))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some("c"))
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }

  test("Queue.WithSize invariant functor instance") {
    for {
      q <- newQueueFromList[String](Nil)
      qq = (q: Queue.WithSize[String]).imap(_.toInt)(_.toString)
      _ <- assertResultF(qq.offer(101).run, true)
      _ <- assertResultF(qq.poll.run, Some(101))
    } yield ()
  }
}

trait QueueWithRemoveSpec[F[_]] extends BaseQueueSpec[F] { this: McasImplSpec =>

  private[data] override type QueueType[A] = RemoveQueue[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    Queue.fromList(RemoveQueue.apply[A])(as)

  test("Queue.WithRemove removers") {
    for {
      q <- newQueueFromList(List.empty[String])
      // ("a", "b", "c", "d", "e")
      remA <- q.enqueueWithRemover("a").run[F]
      _ <- q.enqueueWithRemover("b").run[F]
      remC <- q.enqueueWithRemover("c").run[F]
      remD <- q.enqueueWithRemover("d").run[F]
      _ <- q.enqueueWithRemover("e").run[F]
      _ <- assertResultF(remA.run[F], true)
      _ <- assertResultF(remA.run[F], false)
      _ <- assertResultF(q.poll.run[F], Some("b"))
      _ <- assertResultF(remC.run[F], true)
      _ <- assertResultF(remD.run[F], true)
      _ <- assertResultF(remD.run[F], false)
      _ <- assertResultF(q.poll.run[F], Some("e"))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("Queue.WithRemove removers with enq/deq") {
    for {
      q <- newQueueFromList(List.empty[String])
      remA <- q.enqueueWithRemover("a").run[F]
      _ <- q.enqueueWithRemover("b").run[F]
      remC <- q.enqueueWithRemover("c").run[F]
      remD <- q.enqueueWithRemover("d").run[F]
      _ <- q.enqueueWithRemover("e").run[F]
      _ <- assertResultF(remA.run[F], true)
      _ <- assertResultF(remC.run, true)
      _ <- assertResultF(remD.run, true)
      remF <- q.enqueueWithRemover("f").run[F]
      _ <- assertResultF(q.poll.run[F], Some("b"))
      _ <- assertResultF(remF.run, true)
      _ <- assertResultF(remF.run, false)
      _ <- assertResultF(q.poll.run[F], Some("e"))
      _ <- q.enqueueWithRemover("x").run[F]
      _ <- assertResultF(q.poll.run[F], Some("x"))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("Queue.WithRemove enq then remove") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- q.enqueueWithRemover("a").run[F]
      _ <- q.enqueueWithRemover("b").run[F]
      _ <- q.enqueueWithRemover("c").run[F]
      _ <- assertResultF(q.poll.run[F], Some("a"))
      remX <- q.enqueueWithRemover("x").run[F]
      _ <- assertResultF(q.poll.run[F], Some("b"))
      _ <- assertResultF(remX.run, true)
      _ <- assertResultF(q.poll.run[F], Some("c"))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("Null element") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- q.enqueueWithRemover("a").run[F]
      _ <- q.enqueueWithRemover("b").run[F]
      _ <- q.enqueueWithRemover("c").run[F]
      remNull1 <- q.enqueueWithRemover(null : String).run[F]
      _ <- assertResultF(q.drainOnce, List("a", "b", "c", null))
      _ <- assertResultF(q.poll.run[F], None)
      remNull2 <- q.enqueueWithRemover(null : String).run[F]
      remA1 <- q.enqueueWithRemover("a").run[F]
      remNull3 <- q.enqueueWithRemover(null : String).run[F]
      _ <- q.enqueueWithRemover("b").run[F]
      _ <- q.enqueueWithRemover("a").run[F]
      // null, a, null, b, a
      _ <- assertResultF(remA1.run, true)
      _ <- assertResultF(remA1.run, false)
      // null, null, b, a
      _ <- assertResultF(q.poll.run[F], Some(null))
      // null, b, a
      _ <- assertResultF(remNull1.run, false)
      _ <- assertResultF(remNull2.run, false)
      _ <- assertResultF(remNull3.run, true)
      _ <- assertResultF(remNull3.run, false)
      // b, a
      _ <- assertResultF(q.poll.run[F], Some("b"))
      _ <- assertResultF(q.poll.run[F], Some("a"))
    } yield ()
  }

  test("enqueueWithRemover") {
    for {
      q <- newQueueFromList(List.empty[Int])
      r1 <- q.enqueueWithRemover(1).run[F]
      r2 <- q.enqueueWithRemover(2).run[F]
      r3 <- q.enqueueWithRemover(3).run[F]
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- r1.run[F] // does nothing
      _ <- r2.run[F] // removes 2
      _ <- r2.run[F] // does nothing
      _ <- assertResultF(q.poll.run[F], Some(3))
      _ <- r3.run[F] // does nothing
      _ <- assertResultF(q.poll.run[F], None)
      _ <- r3.run[F] // does nothing
      _ <- assertResultF(q.poll.run[F], None)
      r42 <- q.enqueueWithRemover(42).run[F]
      _ <- r42.run[F] // removes 42
      _ <- q.add(33).run[F]
      _ <- assertResultF(q.poll.run[F], Some(33))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("Concurrent removals") {
    val N = 1024
    val P = 128
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      q <- newQueueFromList(List.empty[String])
      directRemovers <- (1 to N).toList.parTraverseN(P) { idx =>
        q.enqueueWithRemover(idx.toString).run[F].map { remover =>
          // we only want to remove even indices:
          if ((idx % 2) == 0) remover
          else Rxn.pure(true)
        }
      }
      _ <- F.both(
        directRemovers.take(N / 2).parTraverseN(P >>> 1) { r => assertResultF(r.run[F], true) },
        directRemovers.drop(N / 2).parTraverseN(P >>> 1) { r => assertResultF(r.run[F], true) },
      )
      contents <- q.drainOnce
      _ <- assertResultF(q.poll.run[F], None)
      // the odd indices should've remained:
      expected = (1 to N).collect { case i if (i % 2) != 0 => i.toString }.toList
      _ <- assertEqualsF(contents.toSet, expected.toSet)
      _ <- assertEqualsF(contents.length, expected.length)
    } yield ()
  }

  test("RemoveQueue#isEmpty") {
    for {
      q <- newQueueFromList(List.empty[Int])
      _ <- assertResultF(q.isEmpty.run[F], true)
      _ <- assertResultF(q.isEmpty.run[F], true)
      _ <- q.enqueueWithRemover(1).run[F]
      _ <- assertResultF(q.isEmpty.run[F], false)
      _ <- assertResultF(q.isEmpty.run[F], false)
      r2 <- q.enqueueWithRemover(2).run[F]
      r3 <- q.enqueueWithRemover(3).run[F]
      _ <- assertResultF(q.isEmpty.run[F], false)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.isEmpty.run[F], false)
      _ <- assertResultF(r2.run, true)
      _ <- assertResultF(q.isEmpty.run[F], false)
      _ <- assertResultF(r3.run, true)
      _ <- assertResultF(q.isEmpty.run[F], true)
      _ <- q.enqueueWithRemover(42).run[F]
      _ <- assertResultF(q.isEmpty.run[F], false)
      _ <- q.enqueueWithRemover(43).run[F]
      _ <- assertResultF(q.poll.run[F], Some(42))
      _ <- assertResultF(q.isEmpty.run[F], false)
      _ <- assertResultF(q.poll.run[F], Some(43))
      _ <- assertResultF(q.isEmpty.run[F], true)
      _ <- assertResultF(q.isEmpty.run[F], true)
    } yield ()
  }

  test("RemoveQueue#peek") {
    for {
      q <- newQueueFromList(List.empty[Int])
      _ <- assertResultF(q.peek.run[F], None)
      _ <- assertResultF(q.peek.run[F], None)
      _ <- q.enqueueWithRemover(1).run[F]
      _ <- assertResultF(q.peek.run[F], Some(1))
      _ <- assertResultF(q.peek.run[F], Some(1))
      r2 <- q.enqueueWithRemover(2).run[F]
      r3 <- q.enqueueWithRemover(3).run[F]
      _ <- assertResultF((q.peek.flatMap { peeked =>
        q.poll.map { polled => (peeked, polled) }
      }).run[F], (Some(1), Some(1)))
      _ <- assertResultF(q.peek.run[F], Some(2))
      _ <- assertResultF(r2.run, true)
      _ <- assertResultF(q.peek.run[F], Some(3))
      _ <- assertResultF(r3.run, true)
      _ <- assertResultF(q.peek.run[F], None)
      _ <- q.enqueueWithRemover(42).run[F]
      _ <- assertResultF(q.peek.run[F], Some(42))
      _ <- q.enqueueWithRemover(43).run[F]
      _ <- assertResultF(q.peek.run[F], Some(42))
      _ <- assertResultF(q.poll.run[F], Some(42))
      _ <- assertResultF(q.peek.run[F], Some(43))
      _ <- assertResultF(q.poll.run[F], Some(43))
      _ <- assertResultF(q.peek.run[F], None)
      _ <- assertResultF(q.peek.run[F], None)
    } yield ()
  }
}

trait QueueMsSpec[F[_]] extends BaseQueueSpec[F] { this: McasImplSpec =>

  private[data] override type QueueType[A] = MsQueue[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    Queue.fromList(MsQueue[A])(as)

  test("MS-queue lagging tail") {
    for {
      q <- newQueueFromList[Int](Nil)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- q.add(1).run[F]
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- q.add(2).run[F]
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- q.add(3).run[F]
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.poll.run[F], Some(3))
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- (q.add(1) *> q.add(2) *> q.add(3) *> q.add(4)).run[F]
      _ <- assertResultF(q.tailLag.run[F], 0)
      _ <- assertResultF(
        (q.poll * q.poll * q.poll * q.poll).run[F],
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
      _ <- assertResultF(q1.poll.run[F], None)
      q2 <- newQueueFromList[Int](1 :: 2 :: 3 :: Nil)
      _ <- assertResultF(q2.drainOnce, List(1, 2, 3))
      _ <- assertResultF(q2.poll.run[F], None)
    } yield ()
  }

  test("Queue transfer") {
    for {
      q1 <- newQueueFromList(1 :: 2 :: 3 :: Nil)
      q2 <- newQueueFromList(List.empty[Int])
      r = q1.poll.map(_.getOrElse(0)).flatMap(q2.add)
      _ <- r.run[F]
      _ <- r.run[F]
      _ <- assertResultF(q1.drainOnce, List(3))
      _ <- assertResultF(q1.poll.run[F], None)
      _ <- assertResultF(q2.drainOnce, List(1, 2))
      _ <- assertResultF(q2.poll.run[F], None)
    } yield ()
  }

  test("Queue should work correctly") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- assertResultF(q.peek.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)

      _ <- assertResultF(q.poll.run, None)
      _ <- assertResultF(q.poll.run[F], None)

      _ <- q.add("a").run
      _ <- assertResultF(q.peek.run[F], Some("a"))
      _ <- assertResultF(q.peek.run[F], Some("a"))
      _ <- assertResultF(q.poll.run, Some("a"))
      _ <- assertResultF(q.peek.run[F], None)
      _ <- assertResultF(q.poll.run, None)
      _ <- assertResultF(q.peek.run[F], None)

      _ <- q.add("a").run
      _ <- q.add("b").run
      _ <- q.add("c").run
      _ <- assertResultF(q.poll.run, Some("a"))
      _ <- assertResultF(q.peek.run[F], Some("b"))

      _ <- q.add("x").run
      _ <- assertResultF(q.peek.run[F], Some("b"))
      _ <- assertResultF(q.drainOnce, List("b", "c", "x"))
      _ <- assertResultF(q.poll.run, None)
      _ <- assertResultF(q.peek.run[F], None)
    } yield ()
  }

  test("Queue multiple enq/deq in one Rxn") {
    for {
      q <- newQueueFromList(List.empty[String])
      _ <- assertResultF(q.poll.run[F], None)
      rxn = for {
        _ <- q.add("a")
        _ <- q.add("b")
        _ <- q.add("c")
        pa <- q.peek
        a <- q.poll
        b <- q.poll
        _ <- q.add("d")
        pc <- q.peek
        c <- q.poll
        d <- q.poll
      } yield (a, pa, b, c, pc, d)
      abcd <- rxn.run[F]
      _ <- assertEqualsF(abcd, (Some("a"), Some("a"), Some("b"), Some("c"), Some("c"), Some("d")))
      _ <- assertResultF(q.peek.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
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
      val rxn: Rxn[Unit] = items.traverse_ { item => q.add(item) }
      rF.run(rxn)
    }
    def deqTask(q: QueueType[Int]): F[Int] = {
      def goOnce(acc: List[Int]): Rxn[List[Int]] = {
        if (acc.length == RxnSize) {
          Rxn.pure(acc.reverse)
        } else {
          q.peek.flatMap { peeked =>
            q.poll.flatMap {
              case None =>
                Rxn.unsafe.assert(peeked.isEmpty, s"peeked ${peeked}, but polled None") *> (
                  Rxn.unsafe.assert(acc.isEmpty, s"expected empty, got: ${acc}").as(Nil)
                )
              case s @ Some(item) =>
                Rxn.unsafe.assert(peeked == s, s"peeked ${peeked}, but polled ${s}") *> goOnce(item :: acc)
            }
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

  test("Queue co/contra/in-variant functor instances") {
    for {
      q <- newQueueFromList[String](Nil)
      q2 = (q: Queue.Offer[String]).contramap[Int](_.toString)
      _ <- assertResultF(q2.offer(42).run, true)
      _ <- assertResultF(q.poll.run, Some("42"))
      q3 = (q: Queue.Add[String]).contramap[Int](_.toString)
      _ <- assertResultF(q3.add(99).run, ())
      _ <- assertResultF(q.poll.run, Some("99"))
      q4 = (q: Queue.Poll[String]).map(_.toInt)
      _ <- assertResultF(q2.offer(100).run, true)
      _ <- assertResultF(q4.poll.run, Some(100))
      q5 = (q: Queue.SourceSink[String]).imap(_.toInt)(_.toString)
      _ <- assertResultF(q5.offer(101).run, true)
      _ <- assertResultF(q5.poll.run, Some(101))
      q6 = (q: Queue[String]).imap(_.toInt)(_.toString)
      _ <- assertResultF(q6.offer(102).run, true)
      _ <- assertResultF(q6.poll.run, Some(102))
    } yield ()
  }
}
