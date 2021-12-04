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
package async

import scala.math.{ min, max }

import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class OverflowQueueSpec_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEMCAS
  with OverflowQueueSpec[IO]

final class OverflowQueueSpec_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEMCAS
  with OverflowQueueSpec[zio.Task]

trait OverflowQueueSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F]
  with ScalaCheckEffectSuite { this: KCASImplSpec with TestContextSpec[F] =>

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
        q <- OverflowQueue.ringBuffer[F, Int](capacity = c).run[F]
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
      q <- OverflowQueue.ringBuffer[F, Int](capacity = 4).run[F]
      _ <- part1(q)
      _ <- part2(q)
      _ <- part3(q)
      _ <- part4(q)
      _ <- assertResultF(q.size, 0)
    } yield ()
  }

  test("RingBuffer small") {
    for {
      r <- F.delay { OverflowQueue.ringBuffer[F, Int](capacity = 0) }.attempt
      _ <- assertF(r.isLeft)
      q <- OverflowQueue.ringBuffer[F, Int](capacity = 1).run[F]
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
      q <- OverflowQueue.ringBuffer[F, Int](capacity = 3).run[F]
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

  test("RingBuffer#toCats") {
    for {
      q <- OverflowQueue.ringBuffer[F, Int](capacity = 3).run[F]
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
}
