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

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration._

import cats.implicits._

import cats.effect.kernel.{ Outcome, Spawn, Sync }
import cats.effect.{ IO }

final class IOCancelSpec extends BaseSpecIO with IOCancelSpecBase[IO]

object Utils {

  def stoppable[F[_], A](task: AtomicBoolean => F[A])(implicit F: Spawn[F], S: Sync[F]): F[A] = {
    F.flatMap(S.delay { new AtomicBoolean(false) }) { stopSignal =>
      val tsk: F[A] = task(stopSignal)
      F.uncancelable { poll =>
        F.flatMap(F.start(tsk)) { fiber =>
          F.onCancel(
            fa = poll(fiber.joinWithNever),
            fin = F.productR(S.delay { stopSignal.set(true) })(
              // so that cancel backpressures until
              // the task actually observes the signal
              // and stops whatever it is doing:
              F.void(fiber.joinWithNever)
            )
          )
        }
      }
    }
  }
}

// TODO: these tests deadlock on Scala.js, need other tests there
sealed trait IOCancelSpecBase[F[_]]
  extends BaseSpecAsyncF[F]
  with SpecThreadConfinedMCAS {

  import Utils.stoppable

  private[this] final def cancelExpect[A](
    fa: F[A],
    cancelAfter: FiniteDuration,
    expect: Outcome[F, Throwable, A],
  ): F[Unit] = for {
    fib <- fa.start
    _ <- F.sleep(cancelAfter)
    _ <- fib.cancel
    actual <- fib.join
    _ <- assertEqualsF(actual, expect)
  } yield ()

  test("infinite, but observes the stop signal") {
    val t = stoppable { stop =>
      @tailrec
      def go(n: Long): Long = {
        if (stop.get()) {
          println(s"Stopping at ${n}")
          n
        } else {
          go(n + 1)
        }
      }
      F.delay { go(0L) }
    } (F, F)

    cancelExpect(t, cancelAfter = 1.second, expect = Outcome.canceled[F, Throwable, Long])
  }

  test("ignores the stop signal, but finishes after some time") {
    val t = stoppable { _ =>
      @tailrec
      def go(n: Long): Long = {
        if (n >= Int.MaxValue.toLong / 4L) 0L
        else go(n + 1L)
      }
      F.delay { go(0L) }
    } (F, F)

    cancelExpect(t, cancelAfter = 1.millisecond, expect = Outcome.canceled[F, Throwable, Long])
  }

  test("finishes before it could be cancelled") {
    val t = stoppable { _ =>
      F.delay { 0L }
    } (F, F)

    cancelExpect(t, cancelAfter = 1.second, expect = Outcome.succeeded(F.pure(0L)))
  }

  test("cancelling must wait for the task to actually stop") {
    for {
      done <- F.delay { new AtomicBoolean(false) }
      t = stoppable { stop =>
        @tailrec
        def go(n: Long): Long = {
          if (n >= Int.MaxValue.toLong / 4L) 0L
          else if ((n % 0x80000L == 0) && stop.get()) { done.set(true); -1L }
          else go(n + 1L)
        }
        F.delay { go(0L) }
      } (F, F)
      _ <- cancelExpect(t, cancelAfter = 1.millisecond, expect = Outcome.canceled[F, Throwable, Long])
      _ <- assertResultF(F.delay { done.get }, true)
    } yield ()
  }
}
