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

import java.lang.Math.{ min, max }

import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class DroppingQueueSpec_ThreadConfinedMcas
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with DroppingQueueSpec[IO]

trait DroppingQueueSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  def newDq[A](capacity: Int): F[Queue.WithSize[A]] =
    DroppingQueue.apply(capacity).run[F]

  test("Dropping property") {
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
        q <- newDq[Int](c)
        s <- CatsQueue.dropping[F, Int](capacity = c)
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
            q.offer(i).run[F].flatMap { qr =>
              s.tryOffer(i).flatMap { sr =>
                assertEqualsF(qr, sr) *> checkSize(q, s)
              }
            }
          }
        }
      } yield ()
    }
  }

  test("DroppingQueue simple") {
    for {
      q <- newDq[Int](4)
      _ <- assertResultF(q.size.run[F], 0)
      _ <- q.enqueue(1).run[F]
      _ <- assertResultF(q.size.run[F], 1)
      _ <- (q.enqueue(2) * q.enqueue(3)).run[F]
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.offer(4).run[F], true)
      _ <- assertResultF(q.size.run[F], 4)
      _ <- q.enqueue(5).run[F]
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.offer(5).run[F], false)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.size.run[F], 3)
      _ <- assertResultF(q.offer(5).run[F], true)
      _ <- assertResultF(q.size.run[F], 4)
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.poll.run[F], Some(3))
      _ <- assertResultF(q.poll.run[F], Some(4))
      _ <- assertResultF(q.poll.run[F], Some(5))
      _ <- assertResultF(q.size.run[F], 0)
    } yield ()
  }

  test("DroppingQueue multiple ops in one Rxn") {
    for {
      q <- newDq[Int](3)
      _ <- assertResultF(q.size.run[F], 0)
      rxn = (q.enqueue(1) * q.enqueue(2)) *> (
        q.poll
      )
      deqRes <- rxn.run[F]
      _ <- assertEqualsF(deqRes, Some(1))
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }
}
