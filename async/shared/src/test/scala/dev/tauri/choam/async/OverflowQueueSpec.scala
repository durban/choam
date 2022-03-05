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

import scala.math.{ min, max }

import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class OverflowQueueSpec_Strict_ThreadConfinedMCAS_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMCAS
  with StrictOverflowQueueSpec[IO]

final class OverflowQueueSpec_Lazy_ThreadConfinedMCAS_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMCAS
  with LazyOverflowQueueSpec[IO]

trait StrictOverflowQueueSpec[F[_]]
  extends OverflowQueueSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  final override def newRingBuffer[A](capacity: Int): F[OverflowQueue[F, A]] =
    OverflowQueue.ringBuffer[F, A](capacity).run[F]
}

trait LazyOverflowQueueSpec[F[_]]
  extends OverflowQueueSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  final override def newRingBuffer[A](capacity: Int): F[OverflowQueue[F, A]] =
    OverflowQueue.lazyRingBuffer[F, A](capacity).run[F]
}

trait OverflowQueueSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F]
  with ScalaCheckEffectSuite { this: McasImplSpec with TestContextSpec[F] =>

  def newRingBuffer[A](capacity: Int): F[OverflowQueue[F, A]]

  def newDroppingQueue[A](capacity: Int): F[OverflowQueue[F, A]] =
    OverflowQueue.droppingQueue(capacity).run[F]

  test("RingBuffer property") {
    def checkSize[A](q: OverflowQueue[F, A], s: CatsQueue[F, A]): F[Unit] = {
      q.size.flatMap { qs =>
        s.size.flatMap { ss =>
          assertEqualsF(qs, ss)
        }
      }
    }
    PropF.forAllF { (cap: Int, ints: List[Int]) =>
      val c = min(max(cap.abs, 1), 0xffff)
      for {
        q <- newRingBuffer[Int](capacity = c)
        s <- CatsQueue.circularBuffer[F, Int](capacity = c)
        _ <- checkSize(q, s)
        _ <- ints.traverse_ { i =>
          if ((i % 4) == 0) {
            // deq:
            q.tryDeque.run[F].flatMap { qr =>
              s.tryTake.flatMap { sr =>
                assertEqualsF(qr, sr) *> checkSize(q, s)
              }
            }
          } else {
            // enq:
            q.enqueue[F](i) *> s.offer(i) *> (
              checkSize(q, s)
            )
          }
        }
      } yield ()
    }
  }

  test("Dropping property") {
    def checkSize[A](q: OverflowQueue[F, A], s: CatsQueue[F, A]): F[Unit] = {
      q.size.flatMap { qs =>
        s.size.flatMap { ss =>
          assertEqualsF(qs, ss)
        }
      }
    }
    PropF.forAllF { (cap: Int, ints: List[Int]) =>
      val c = min(max(cap.abs, 1), 0xffff)
      for {
        q <- newDroppingQueue[Int](capacity = c)
        s <- CatsQueue.dropping[F, Int](capacity = c)
        _ <- checkSize(q, s)
        _ <- ints.traverse_ { i =>
          if ((i % 4) == 0) {
            // deq:
            q.tryDeque.run[F].flatMap { qr =>
              s.tryTake.flatMap { sr =>
                assertEqualsF(qr, sr) *> checkSize(q, s)
              }
            }
          } else {
            // enq:
            q.tryEnqueue[F](i).flatMap { qr =>
              s.tryOffer(i).flatMap { sr =>
                assertEqualsF(qr, sr) *> checkSize(q, s)
              }
            }
          }
        }
      } yield ()
    }
  }

  test("RingBuffer simple") {
    // OK, scalac overflows the stack if
    // a `for` is sufficiently long, so
    // we split the test into parts:
    def part1(q: OverflowQueue[F, Int]): F[Unit] = for {
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      f1 <- q.deque.start
      _ <- this.tickAll
      _ <- assertResultF(q.size, 0)
      _ <- q.enqueue[F](1)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(q.size, 0)
      _ <- q.enqueue[F](2)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](3)
      _ <- assertResultF(q.size, 2)
      _ <- assertResultF(q.deque, 2)
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.deque, 3)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
    def part2(q: OverflowQueue[F, Int]): F[Unit] = for {
      _ <- assertResultF(q.size, 0)
      _ <- q.enqueue[F](4)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](5)
      _ <- assertResultF(q.size, 2)
      _ <- q.enqueue[F](6)
      _ <- assertResultF(q.size, 3)
      _ <- q.enqueue[F](7) // full
      _ <- assertResultF(q.size, 4)
      _ <- assertResultF(q.deque, 4)
      _ <- assertResultF(q.size, 3)
      _ <- assertResultF(q.deque, 5)
      _ <- assertResultF(q.size, 2)
      _ <- assertResultF(q.deque, 6)
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.deque, 7)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
    def part3(q: OverflowQueue[F, Int]): F[Unit] = for {
      _ <- assertResultF(q.size, 0)
      f1 <- q.deque.start
      _ <- this.tickAll
      f2 <- q.deque.start
      _ <- this.tickAll
      f3 <- q.deque.start
      _ <- this.tickAll
      _ <- q.enqueue[F](8)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f1.joinWithNever, 8)
      _ <- q.enqueue[F](9)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f2.joinWithNever, 9)
      _ <- q.enqueue[F](10)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f3.joinWithNever, 10)
      _ <- q.enqueue[F](11)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](12)
      _ <- assertResultF(q.size, 2)
      _ <- q.enqueue[F](13)
      _ <- assertResultF(q.size, 3)
      _ <- assertResultF(q.deque, 11)
      _ <- assertResultF(q.size, 2)
      _ <- assertResultF(q.deque, 12)
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.deque, 13)
      _ <- assertResultF(q.size, 0)
    } yield ()
    def part4(q: OverflowQueue[F, Int]): F[Unit] = for {
      _ <- assertResultF(q.size, 0)
      _ <- q.enqueue[F](14)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](15)
      _ <- assertResultF(q.size, 2)
      _ <- q.enqueue[F](16)
      _ <- assertResultF(q.size, 3)
      _ <- q.enqueue[F](17) // full
      _ <- assertResultF(q.size, 4)
      _ <- q.enqueue[F](18) // overwrites 14
      _ <- assertResultF(q.size, 4)
      _ <- assertResultF(q.deque, 15)
      _ <- assertResultF(q.size, 3)
      _ <- assertResultF(q.deque, 16)
      _ <- assertResultF(q.size, 2)
      _ <- q.enqueue[F](19)
      _ <- assertResultF(q.size, 3)
      _ <- q.enqueue[F](20) // full
      _ <- assertResultF(q.size, 4)
      _ <- q.enqueue[F](21) // overwrites 17
      _ <- assertResultF(q.size, 4)
      _ <- assertResultF(q.deque, 18)
      _ <- assertResultF(q.size, 3)
      _ <- assertResultF(q.deque, 19)
      _ <- assertResultF(q.size, 2)
      _ <- assertResultF(q.deque, 20)
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.deque, 21)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
    for {
      q <- newRingBuffer[Int](capacity = 4)
      _ <- part1(q)
      _ <- part2(q)
      _ <- part3(q)
      _ <- part4(q)
      _ <- assertResultF(q.size, 0)
    } yield ()
  }

  test("RingBuffer small") {
    for {
      r <- F.delay { newRingBuffer[Int](capacity = 0) }.attempt
      _ <- assertF(r.isLeft)
      q <- newRingBuffer[Int](capacity = 1)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      f1 <- q.deque.start
      _ <- this.tickAll
      _ <- q.enqueue[F](1)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- q.enqueue[F](2)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](3)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](4)
      _ <- assertResultF(q.size, 1)
      _ <- q.enqueue[F](5)
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.deque, 5)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      f2 <- q.deque.start
      _ <- this.tickAll
      f3 <- q.deque.start
      _ <- this.tickAll
      _ <- q.enqueue[F](6)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f2.joinWithNever, 6)
      _ <- q.enqueue[F](7)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(f3.joinWithNever, 7)
      _ <- assertResultF(q.size, 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("RingBuffer cancellation") {
    for {
      _ <- this.assumeNotZio // TODO: https://github.com/zio/interop-cats/issues/509
      q <- newRingBuffer[Int](capacity = 3)
      f1 <- q.deque.start
      _ <- this.tickAll
      f2 <- q.deque.start
      _ <- this.tickAll
      f3 <- q.deque.start
      _ <- this.tickAll
      _ <- f2.cancel
      _ <- q.enqueue(1)
      _ <- q.enqueue(2)
      _ <- q.enqueue(3)
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(f3.joinWithNever, 2)
      _ <- assertResultF(q.size, 1)
      _ <- assertResultF(q.deque, 3)
      _ <- assertResultF(q.size, 0)
    } yield ()
  }

  test("RingBuffer multiple ops in one Rxn") {
    for {
      q <- newRingBuffer[Int](capacity = 3)
      f1 <- q.deque.start
      _ <- this.tickAll
      f2 <- q.deque.start
      _ <- this.tickAll
      rxn = (q.enqueue.provide(1) * q.enqueue.provide(2) * q.enqueue.provide(3)) *> (
        q.tryDeque
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(3))
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(f2.joinWithNever, 2)
      _ <- assertResultF(q.size, 0)
    } yield ()
  }

  test("RingBuffer#toCats") {
    for {
      q <- newRingBuffer[Int](capacity = 3)
      cq = q.toCats
      f <- cq.take.start
      _ <- this.tickAll
      _ <- cq.offer(1)
      _ <- assertResultF(cq.size, 0)
      _ <- assertResultF(f.joinWithNever, 1)
      _ <- cq.offer(2)
      _ <- cq.offer(3)
      _ <- cq.offer(4)
      _ <- cq.offer(5)
      _ <- assertResultF(cq.take, 3)
      _ <- assertResultF(cq.take, 4)
      _ <- assertResultF(cq.take, 5)
      _ <- assertResultF(cq.size, 0)
    } yield ()
  }

  test("DroppingQueue simple") {
    for {
      q <- newDroppingQueue[Int](4)
      _ <- assertResultF(q.size, 0)
      _ <- q.enqueue[F](1)
      _ <- assertResultF(q.size, 1)
      _ <- (q.enqueue × q.enqueue)[F]((2, 3))
      _ <- assertResultF(q.size, 3)
      _ <- assertResultF(q.tryEnqueue[F](4), true)
      _ <- assertResultF(q.size, 4)
      _ <- q.enqueue[F](5)
      _ <- assertResultF(q.size, 4)
      _ <- assertResultF(q.tryEnqueue[F](5), false)
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- assertResultF(q.size, 3)
      _ <- assertResultF(q.tryEnqueue[F](5), true)
      _ <- assertResultF(q.size, 4)
      _ <- assertResultF(q.deque, 2)
      _ <- assertResultF(q.deque, 3)
      _ <- assertResultF(q.deque, 4)
      _ <- assertResultF(q.tryDeque.run[F], Some(5))
      _ <- assertResultF(q.size, 0)
    } yield ()
  }

  test("DroppingQueue multiple ops in one Rxn") {
    for {
      q <- newDroppingQueue[Int](3)
      _ <- assertResultF(q.size, 0)
      rxn = (q.enqueue.provide(1) * q.enqueue.provide(2)) *> (
        q.tryDeque
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(1))
      _ <- assertResultF(q.deque, 2)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }
}
