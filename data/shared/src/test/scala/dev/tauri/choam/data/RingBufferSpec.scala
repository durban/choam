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

import java.lang.Math.{ min, max }

import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class RingBufferSpec_Strict_ThreadConfinedMcas
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with StrictRingBufferSpec[IO]

final class RingBufferSpec_Lazy_ThreadConfinedMcas
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with LazyRingBufferSpec[IO]

trait StrictRingBufferSpec[F[_]] extends RingBufferSpec[F] { this: McasImplSpec =>
  final override def newRingBuffer[A](capacity: Int): F[Queue.WithSize[A]] =
    RingBuffer.apply[A](capacity).run[F].widen
}

trait LazyRingBufferSpec[F[_]] extends RingBufferSpec[F] { this: McasImplSpec =>
  final override def newRingBuffer[A](capacity: Int): F[Queue.WithSize[A]] =
    RingBuffer.lazyRingBuffer[A](capacity).run[F].widen
}

trait RingBufferSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  def newRingBuffer[A](capacity: Int): F[Queue.WithSize[A]]

  test("RingBuffer property") {
    def checkSize[A](q: Queue.WithSize[A], s: CatsQueue[F, A]): F[Unit] = {
      q.size.run[F].flatMap { qs =>
        s.size.flatMap { ss =>
          assertEqualsF(qs, ss)
        }
      }
    }
    PropF.forAllF { (cap: Int, ints: List[Int]) =>
      val maxCap = this.platform match {
        case Jvm | Js => 0x7fff
        case Native => 0x1fff
      }
      val c = min(max(cap.abs, 1), maxCap)
      for {
        q <- newRingBuffer[Int](c)
        s <- CatsQueue.circularBuffer[F, Int](capacity = c)
        _ <- checkSize(q, s)
        _ <- ints.traverse_ { i =>
          if ((i % 4) == 0) {
            // deq:
            q.poll.run[F].flatMap { qr =>
              s.tryTake.flatMap { sr =>
                assertEqualsF(qr, sr) *> checkSize(q, s)
              }
            }
          } else {
            // enq:
            q.add(i).run[F] *> s.offer(i) *> (
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
    def part1(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.add(1).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.add(2).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(3).run[F]
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(3))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    def part2(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- q.add(4).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(5).run[F]
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.add(6).run[F]
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.add(7).run[F] // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.poll.run[F], Some(4))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.poll.run[F], Some(5))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.poll.run[F], Some(6))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(7))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    def part3(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- q.add(8).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(9).run[F]
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.add(10).run[F]
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.add(11).run[F] // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.add(12).run[F] // overwrites 8
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.add(13).run[F] // overwrites 9
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.poll.run[F], Some(10))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.poll.run[F], Some(11))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.poll.run[F], Some(12))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(13))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    def part4(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- q.add(14).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(15).run[F]
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.add(16).run[F]
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.add(17).run[F] // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.add(18).run[F] // overwrites 14
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.poll.run[F], Some(15))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.poll.run[F], Some(16))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.add(19).run[F]
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.add(20).run[F] // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.add(21).run[F] // overwrites 17
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.poll.run[F], Some(18))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.poll.run[F], Some(19))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.poll.run[F], Some(20))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(21))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
    for {
      q <- newRingBuffer[Int](4)
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
      q <- newRingBuffer[Int](1)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- q.add(1).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- q.add(2).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(3).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(4).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(5).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(5))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- q.add(6).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.add(7).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.poll.run[F], Some(7))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("RingBuffer multiple ops in one Rxn") {
    for {
      q <- newRingBuffer[Int](3)
      _ <- assertResultF(q.size.run[F], 0)
      rxn = (q.add(1) * q.add(2)) *> (
        q.poll
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(1))
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }
}
