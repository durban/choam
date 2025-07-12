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

import java.lang.Math.{ min, max }

import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class OverflowQueueSpec_Strict_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with StrictOverflowQueueSpec[IO]

final class OverflowQueueSpec_Lazy_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with LazyOverflowQueueSpec[IO]

trait StrictOverflowQueueSpec[F[_]]
  extends OverflowQueueSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  final override def newRingBuffer[A](capacity: Int): F[OverflowQueue[A]] =
    OverflowQueue.ringBuffer[A](capacity).run[F]
}

trait LazyOverflowQueueSpec[F[_]]
  extends OverflowQueueSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  final override def newRingBuffer[A](capacity: Int): F[OverflowQueue[A]] =
    OverflowQueue.lazyRingBuffer[A](capacity).run[F]
}

trait OverflowQueueSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec & TestContextSpec[F] =>

  def newRingBuffer[A](capacity: Int): F[OverflowQueue[A]]

  def newDroppingQueue[A](capacity: Int): F[OverflowQueue[A]] =
    OverflowQueue.droppingQueue(capacity).run[F]

  final val Max =
    2048

  test("RingBuffer property") {
    def checkSize[A](q: OverflowQueue[A], qc: CatsQueue[F, A], s: CatsQueue[F, A]): F[Unit] = {
      q.size.run[F].flatMap { qs =>
        qc.size.flatMap { qcs =>
          s.size.flatMap { ss =>
            assertEqualsF(qs, ss) *> assertEqualsF(qcs, ss)
          }
        }
      }
    }
    PropF.forAllF { (cap: Int, _ints: List[Int]) =>
      val c = min(max(cap.abs, 1), Max)
      val ints = _ints.take(2 * Max)
      for {
        q <- newRingBuffer[Int](capacity = c)
        qc <- newRingBuffer[Int](capacity = c).map(_.toCats)
        s <- CatsQueue.circularBuffer[F, Int](capacity = c)
        _ <- checkSize(q, qc, s)
        _ <- ints.traverse_ { i =>
          if ((i % 4) == 0) {
            // deq:
            q.poll.run[F].flatMap { qr =>
              qc.tryTake.flatMap { qcr =>
                s.tryTake.flatMap { sr =>
                  assertEqualsF(qr, sr) *> assertEqualsF(qcr, sr) *> checkSize(q, qc, s)
                }
              }
            }
          } else {
            // enq:
            q.enqueueAsync[F](i) *> qc.offer(i) *> s.offer(i) *> (
              checkSize(q, qc, s)
            )
          }
        }
      } yield ()
    }
  }

  test("Dropping property") {
    def checkSize[A](q: OverflowQueue[A], qc: CatsQueue[F, A], s: CatsQueue[F, A]): F[Unit] = {
      q.size.run[F].flatMap { qs =>
        qc.size.flatMap { qcs =>
          s.size.flatMap { ss =>
            assertEqualsF(qs, ss) *> assertEqualsF(qcs, ss)
          }
        }
      }
    }
    PropF.forAllF { (cap: Int, _ints: List[Int]) =>
      val c = min(max(cap.abs, 1), Max)
      val ints = _ints.take(2 * Max)
      for {
        q <- newDroppingQueue[Int](capacity = c)
        qc <- newDroppingQueue[Int](capacity = c).map(_.toCats)
        s <- CatsQueue.dropping[F, Int](capacity = c)
        _ <- checkSize(q, qc, s)
        _ <- ints.traverse_ { i =>
          if ((i % 4) == 0) {
            // deq:
            q.poll.run[F].flatMap { qr =>
              qc.tryTake.flatMap { qcr =>
                s.tryTake.flatMap { sr =>
                  assertEqualsF(qr, sr) *> assertEqualsF(qcr, sr) *> checkSize(q, qc, s)
                }
              }
            }
          } else {
            // enq:
            q.offer(i).run[F].flatMap { qr =>
              qc.tryOffer(i).flatMap { qcr =>
                s.tryOffer(i).flatMap { sr =>
                  assertEqualsF(qr, sr) *> assertEqualsF(qcr, sr) *> checkSize(q, qc, s)
                }
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
    def part1(q: OverflowQueue[Int]): F[Unit] = for {
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      f1 <- q.take.start
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueueAsync[F](1)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueueAsync[F](2)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](3)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.take, 2)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, 3)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    def part2(q: OverflowQueue[Int]): F[Unit] = for {
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueueAsync[F](4)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](5)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueueAsync[F](6)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueueAsync[F](7) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.take, 4)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.take, 5)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.take, 6)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, 7)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    def part3(q: OverflowQueue[Int]): F[Unit] = for {
      _ <- assertResultF(q.size.run[F], 0)
      f1 <- q.take.start
      _ <- this.tickAll
      f2 <- q.take.start
      _ <- this.tickAll
      f3 <- q.take.start
      _ <- this.tickAll
      _ <- q.enqueueAsync[F](8)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f1.joinWithNever, 8)
      _ <- q.enqueueAsync[F](9)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f2.joinWithNever, 9)
      _ <- q.enqueueAsync[F](10)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f3.joinWithNever, 10)
      _ <- q.enqueueAsync[F](11)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](12)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueueAsync[F](13)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.take, 11)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.take, 12)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, 13)
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
    def part4(q: OverflowQueue[Int]): F[Unit] = for {
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueueAsync[F](14)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](15)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueueAsync[F](16)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueueAsync[F](17) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueueAsync[F](18) // overwrites 14
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.take, 15)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.take, 16)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueueAsync[F](19)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueueAsync[F](20) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueueAsync[F](21) // overwrites 17
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.take, 18)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.take, 19)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.take, 20)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, 21)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    for {
      q <- newRingBuffer[Int](capacity = 4)
      _ <- part1(q)
      _ <- part2(q)
      _ <- part3(q)
      _ <- part4(q)
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }

  test("RingBuffer small") {
    for {
      r <- F.delay { newRingBuffer[Int](capacity = 0) }.attempt
      _ <- assertF(r.isLeft)
      q <- newRingBuffer[Int](capacity = 1)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      f1 <- q.take.start
      _ <- this.tickAll
      _ <- q.enqueueAsync[F](1)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- q.enqueueAsync[F](2)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](3)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](4)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueueAsync[F](5)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, 5)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      f2 <- q.take.start
      _ <- this.tickAll
      f3 <- q.take.start
      _ <- this.tickAll
      _ <- q.enqueueAsync[F](6)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f2.joinWithNever, 6)
      _ <- q.enqueueAsync[F](7)
      _ <- this.tickAll
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(f3.joinWithNever, 7)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("RingBuffer cancellation") {
    for {
      q <- newRingBuffer[Int](capacity = 3)
      f1 <- q.take.start
      _ <- this.tickAll
      f2 <- q.take.start
      _ <- this.tickAll
      f3 <- q.take.start
      _ <- this.tickAll
      _ <- f2.cancel
      _ <- this.tickAll
      _ <- q.enqueueAsync(1)
      _ <- this.tickAll
      _ <- q.enqueueAsync(2)
      _ <- this.tickAll
      _ <- q.enqueueAsync(3)
      _ <- this.tickAll
      _ <- assertResultF(f1.joinWithNever, 1)
      _ <- assertResultF(f3.joinWithNever, 2)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.take, 3)
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }

  test("RingBuffer multiple ops in one Rxn") {
    for {
      q <- newRingBuffer[Int](capacity = 3)
      f1 <- q.take.start
      _ <- this.tickAll
      f2 <- q.take.start
      _ <- this.tickAll
      rxn = (q.add(1) * q.add(2) * q.add(3)) *> (
        q.poll
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(1))
      // since `rxn` awakes all fibers in its post-commit actions, their order is non-deterministic:
      v1 <- f1.joinWithNever
      v2 <- f2.joinWithNever
      _ <- assertEqualsF(Set(v1, v2), Set(2, 3))
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }

  test("RingBuffer#toCats") {
    for {
      q <- newRingBuffer[Int](capacity = 3)
      cq = q.toCats
      f <- cq.take.start
      _ <- this.tickAll
      _ <- cq.offer(1)
      _ <- this.tickAll
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
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.add(1).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- (q.add(2) * q.add(3)).run[F]
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.offer(4).run[F], true)
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.add(5).run[F]
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.offer(5).run[F], false)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.offer(5).run[F], true)
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.take, 2)
      _ <- assertResultF(q.take, 3)
      _ <- assertResultF(q.take, 4)
      _ <- assertResultF(q.poll.run[F], Some(5))
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }

  test("DroppingQueue multiple ops in one Rxn") {
    for {
      q <- newDroppingQueue[Int](3)
      _ <- assertResultF(q.size.run[F], 0)
      rxn = (q.add(1) * q.add(2)) *> (
        q.poll
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(1))
      _ <- assertResultF(q.take, 2)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }
}
