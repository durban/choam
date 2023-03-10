/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package skiplist

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ThreadLocalRandom, ConcurrentSkipListSet }

import scala.concurrent.duration._

import cats.syntax.all._
import cats.effect.kernel.Ref
import cats.effect.IO

import munit.CatsEffectSuite

final class SkipListParallelSpec extends CatsEffectSuite {

  final val N = 100000
  final val DELAY = 10000L // ns

  private[this] val RightUnit =
    Right(())

  override def munitIOTimeout: Duration =
    2 * super.munitIOTimeout

  private def drainUntilDone(m: TimerSkipList, done: Ref[IO, Boolean]): IO[Unit] = {
    val pollSome: IO[Long] = IO {
      while ({
        val cb = m.pollFirstIfTriggered(System.nanoTime())
        if (cb ne null) {
          cb(RightUnit)
          true
        } else false
      }) {}
      m.peekFirstDelay(System.nanoTime())
    }
    def go(lastOne: Boolean): IO[Unit] = pollSome.flatMap { next =>
      if (next == Long.MinValue) IO.cede
      else if (next < 0L) IO.unit
      else IO.sleep(next.nanos)
    } *> {
      if (lastOne) IO.unit
      else done.get.ifM(go(lastOne = true), IO.cede *> go(lastOne = false))
    }

    go(lastOne = false)
  }

  test("Parallel insert/pollFirstIfTriggered") {
    IO.ref(false).flatMap { done =>
      IO { (new TimerSkipList, new AtomicLong) }.flatMap {
        case (m, ctr) =>

          val insert = IO {
            m.insert(
              now = System.nanoTime(),
              delay = DELAY,
              callback = { _ => ctr.getAndIncrement; () },
              tlr = ThreadLocalRandom.current(),
            )
          }
          val inserts = (insert.parReplicateA_(N) *> IO.sleep(2 * DELAY.nanos)).guarantee(done.set(true))

          val polls = drainUntilDone(m, done).parReplicateA_(2)

          IO.both(inserts, polls).flatMap { _ =>
            IO.sleep(0.5.second) *> IO {
              assert(m.pollFirstIfTriggered(System.nanoTime()) eq null)
              assertEquals(ctr.get(), N.toLong)
            }
          }
      }
    }
  }

  test("Parallel insert/cancel") {
    IO.ref(false).flatMap { done =>
      IO { (new TimerSkipList, new ConcurrentSkipListSet[Int]) }.flatMap {
        case (m, called) =>

          def insert(id: Int): IO[Runnable] = IO {
            val now = System.nanoTime()
            val canceller = m.insert(
              now = now,
              delay = DELAY,
              callback = { _ => called.add(id); () },
              tlr = ThreadLocalRandom.current(),
            )
            canceller
          }

          def cancel(c: Runnable): IO[Unit] = IO {
            c.run()
          }

          val firstBatch = (0 until N).toList
          val secondBatch = (N until (2 * N)).toList

          for {
            // add the first N callbacks:
            cancellers <- firstBatch.traverse(insert)
            // then race removing those, and adding another N:
            _ <- IO.both(
              cancellers.parTraverse(cancel),
              secondBatch.parTraverse(insert),
            )
            // since the fibers calling callbacks
            // are not running yet, the cancelled
            // ones must never be invoked
            _ <- IO.both(
              IO.sleep(2 * DELAY.nanos).guarantee(done.set(true)),
              drainUntilDone(m, done).parReplicateA_(2),
            )
            _ <- IO {
              assert(m.pollFirstIfTriggered(System.nanoTime()) eq null)
              // no cancelled callback should've been called,
              // and all the other ones must've been called:
              val calledIds = {
                val b = Set.newBuilder[Int]
                val it = called.iterator()
                while (it.hasNext()) {
                  b += it.next()
                }
                b.result()
              }
              assertEquals(calledIds, secondBatch.toSet)
            }
          } yield ()
      }
    }
  }

  test("Random racing sleeps") {
    IO.ref(false).flatMap { dummy =>
      IO { (new TimerSkipList) }.flatMap {
        case (m) =>

          def mySleep(d: FiniteDuration): IO[Unit] = IO.async[Unit] { cb =>
            IO {
              val canceller = m.insert(System.nanoTime(), d.toNanos, cb, ThreadLocalRandom.current())
              Some(IO { canceller.run() })
            }
          }

          def randomSleep: IO[Unit] = IO.defer {
            val n = ThreadLocalRandom.current().nextInt(2000000)
            mySleep(n.micros) // less than 2 seconds
          }

          def race(ios: List[IO[Unit]]): IO[Unit] = {
            ios match {
              case head :: tail => tail.foldLeft(head) { (x, y) => IO.race(x, y).void }
              case Nil => IO.unit
            }
          }

          def all(ios: List[IO[Unit]]): IO[Unit] =
            ios.parSequence_

          val N = 1000

          for {
            // start the "scheduler":
            sch <- drainUntilDone(m, dummy).parReplicateA_(2).start
            // race a lot of "sleeps", it must not hang
            // (this includes inserting and cancelling
            // a lot of callbacks into the skip list,
            // thus hopefully stressing the data structure):
            _ <- all(List.fill(N) {
              race(List.fill(N) { randomSleep })
            })
            _ <- sch.cancel
            _ <- IO {
              assert(m.pollFirstIfTriggered(System.nanoTime()) eq null)
            }
          } yield ()
      }
    }
  }
}
