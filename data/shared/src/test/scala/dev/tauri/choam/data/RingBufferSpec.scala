/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.math.{ min, max }

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
      val c = min(max(cap.abs, 1), 0x7fff)
      for {
        q <- newRingBuffer[Int](c)
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
    def part1(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueue[F](1)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueue[F](2)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](3)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.tryDeque.run[F], Some(2))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(3))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
    def part2(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- q.enqueue[F](4)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](5)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueue[F](6)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueue[F](7) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.tryDeque.run[F], Some(4))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.tryDeque.run[F], Some(5))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.tryDeque.run[F], Some(6))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(7))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
    def part3(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- q.enqueue[F](8)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](9)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueue[F](10)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueue[F](11) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueue[F](12) // overwrites 8
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueue[F](13) // overwrites 9
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.tryDeque.run[F], Some(10))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.tryDeque.run[F], Some(11))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.tryDeque.run[F], Some(12))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(13))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
    def part4(q: Queue.WithSize[Int]): F[Unit] = for {
      _ <- q.enqueue[F](14)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](15)
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueue[F](16)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueue[F](17) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueue[F](18) // overwrites 14
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.tryDeque.run[F], Some(15))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.tryDeque.run[F], Some(16))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- q.enqueue[F](19)
      _ <- assertResultF(q.size.run[F], 3)
      _ <- q.enqueue[F](20) // full
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueue[F](21) // overwrites 17
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.tryDeque.run[F], Some(18))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.tryDeque.run[F], Some(19))
      _ <- assertResultF(q.size.run[F], 2)
      _ <- assertResultF(q.tryDeque.run[F], Some(20))
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(21))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
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
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- q.enqueue[F](1)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- q.enqueue[F](2)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](3)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](4)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](5)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(5))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- q.enqueue[F](6)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- q.enqueue[F](7)
      _ <- assertResultF(q.size.run[F], 1)
      _ <- assertResultF(q.tryDeque.run[F], Some(7))
      _ <- assertResultF(q.size.run[F], 0)
      _ <- assertResultF(q.tryDeque.run[F], None)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("RingBuffer multiple ops in one Rxn") {
    for {
      q <- newRingBuffer[Int](3)
      _ <- assertResultF(q.size.run[F], 0)
      rxn = (q.enqueue.provide(1) * q.enqueue.provide(2)) *> (
        q.tryDeque
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(1))
      _ <- assertResultF(q.tryDeque.run[F], Some(2))
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }
}
