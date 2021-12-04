/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._

import cats.effect.IO

final class QueueSpec_NaiveKCAS_IO
  extends BaseSpecIO
  with QueueSpec[IO]
  with SpecNaiveKCAS

final class QueueSpec_NaiveKCAS_ZIO
  extends BaseSpecZIO
  with QueueSpec[zio.Task]
  with SpecNaiveKCAS

final class QueueSpec_EMCAS_IO
  extends BaseSpecIO
  with QueueSpec[IO]
  with SpecEMCAS

final class QueueSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with QueueSpec[zio.Task]
  with SpecEMCAS

// TODO: doesn't work with NaiveKCAS (RemoveQueue uses `null` as sentinel)
// final class QueueWithRemoveSpec_NaiveKCAS_IO
//   extends BaseSpecIO
//   with QueueWithRemoveSpec[IO]
//   with SpecNaiveKCAS

final class QueueWithRemoveSpec_EMCAS_IO
  extends BaseSpecIO
  with QueueWithRemoveSpec[IO]
  with SpecEMCAS

final class QueueWithRemoveSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with QueueWithRemoveSpec[zio.Task]
  with SpecEMCAS

trait QueueWithRemoveSpec[F[_]] extends BaseQueueSpec[F] { this: KCASImplSpec =>

  override type QueueType[A] = Queue.WithRemove[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    Queue.withRemoveFromList(as).run[F]

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

  test("Illegal null element") {
    for {
      q <- newQueueFromList(List("a", "b", "c"))
      r <- q.enqueue[F](null : String).attempt
      _ <- assertF(r.isLeft)
    } yield ()
  }
}

trait QueueSpec[F[_]] extends BaseQueueSpec[F] { this: KCASImplSpec =>

  override type QueueType[A] = Queue[A]

  protected override def newQueueFromList[A](as: List[A]): F[this.QueueType[A]] =
    Queue.fromList(as).run[F]
}

trait BaseQueueSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  type QueueType[A] <: Queue[A]

  protected def newQueueFromList[A](as: List[A]): F[this.QueueType[A]]

  test("MichaelScottQueue should include the elements passed to its constructor") {
    for {
      q1 <- newQueueFromList[Int](Nil)
      _ <- assertResultF(q1.tryDeque.run[F], None)
      q2 <- newQueueFromList[Int](1 :: 2 :: 3 :: Nil)
      _ <- assertResultF(q2.drainOnce, List(1, 2, 3))
      _ <- assertResultF(q2.tryDeque.run[F], None)
    } yield ()
  }

  test("MichaelScottQueue transfer") {
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

  test("Michael-Scott queue should work correctly") {
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

  test("Michael-Scott queue should allow multiple producers and consumers") {
    val max = 5000
    for {
      q <- newQueueFromList(List.empty[String])
      produce = F.blocking {
        for (i <- 0 until max) {
          q.enqueue.unsafePerform(i.toString, this.kcasImpl)
        }
      }
      cs <- F.delay { new ConcurrentLinkedQueue[String] }
      stop <- F.delay { new AtomicBoolean(false) }
      consume = F.blocking {
        @tailrec
        def go(last: Boolean = false): Unit = {
          q.tryDeque.unsafePerform((), this.kcasImpl) match {
            case Some(s) =>
              cs.offer(s)
              go(last = last)
            case None =>
              if (stop.get()) {
                if (last) {
                  // we're done:
                  ()
                } else {
                  // read one last time:
                  go(last = true)
                }
              } else {
                // retry:
                go(last = false)
              }
          }
        }
        go()
      }
      tsk = for {
        p1 <- produce.start
        c1 <- consume.start
        p2 <- produce.start
        c2 <- consume.start
        _ <- p1.joinWithNever
        _ <- p2.joinWithNever
        _ <- F.delay { stop.set(true) }
        _ <- c1.joinWithNever
        _ <- c2.joinWithNever
      } yield ()

      _ <- tsk.guarantee(F.delay { stop.set(true) })

      _ <- assertEqualsF(
        cs.asScala.toVector.sorted,
        (0 until max).toVector.flatMap(n => Vector(n.toString, n.toString)).sorted
      )
      _ <- F.delay {
        this.kcasImpl.printStatistics(System.out.println(_))
      }
    } yield ()
  }
}
