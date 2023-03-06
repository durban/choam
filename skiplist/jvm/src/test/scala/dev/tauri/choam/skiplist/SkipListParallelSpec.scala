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
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import cats.syntax.all._
import cats.effect.kernel.Ref
import cats.effect.IO

import munit.CatsEffectSuite

final class SkipListParallelSpec extends CatsEffectSuite {

  final val N = 100000
  final val DELAY = 1000L

  private[this] val RightUnit =
    Right(())

  private def drainUntilDone(m: TimerSkipList, done: Ref[IO, Boolean]): IO [Unit] = {
    val pollSome = IO {
      while ({
        val cb = m.pollFirstIfTriggered(System.nanoTime())
        if (cb ne null) {
          cb(RightUnit)
          true
        } else false
      }) {}
    }
    def go: IO[Unit] = pollSome *> done.get.ifM(IO.unit, IO.cede *> go)
    go
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
              tlr = ThreadLocalRandom.current()
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
}
