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

import scala.concurrent.duration._

import cats.effect.{ IO, Outcome }

import core.{ Ref, AsyncReactiveSpec }

final class WaitListSpec_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with WaitListSpec[IO]

trait WaitListSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  test("WaitList around a Ref") {
    for {
      ref <- Ref[Option[Int]](None).run[F]
      wl <- WaitList[Int](
        ref.get,
        ref.getAndSet.contramap[Int](Some(_)).void
      ).run[F]
      f1 <- wl.asyncGet.start
      _ <- this.tickAll
      f2 <- wl.asyncGet.start
      _ <- this.tickAll
      _ <- wl.set0[F](42)
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- wl.set0[F](21)
      _ <- assertResultF(f2.joinWithNever, 21)
    } yield ()
  }

  test("deque and tryDeque race") {
    for {
      q <- AsyncQueue.unbounded[String].run[F]
      fib1 <- q.deque[F, String].start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.deque[F, String].start
      _ <- this.tickAll // wait for fiber to suspend
      // to be fair(er), the item should be received by the suspended fiber, and NOT `tryDeque`
      _ <- assertResultF(F.both(q.tryDeque.run[F], q.enqueue("foo")), (None, ()))
      _ <- assertResultF(fib1.joinWithNever, "foo")
      _ <- fib2.cancel
    } yield ()
  }

  test("deque wakes up, then goes to sleep again") {
    for {
      q <- AsyncQueue.unbounded[String].run[F]
      fib1 <- q.deque[F, String].start
      _ <- this.tickAll // wait for fiber to suspend
      _ <- q.enqueue("foo") // this will wake up the fiber, but:
      maybeResult <- q.tryDeque.run[F] // this has a chance of overtaking the fiber
      // (depending on which task the ticked runtime runs first)
      _ <- this.tickAll // fiber either completes, or goes back to sleep
      _ <- maybeResult match {
        case Some(item) =>
          // fiber lost, it is sleeping now
          assertEqualsF(item, "foo") *> fib1.cancel // this will hang if it's uncancelable (which is a bug)
        case None =>
          // fiber won, it's done now
          assertResultF(fib1.joinWithNever, "foo")
      }
    } yield ()
  }

  test("deque gets cancelled right after (correctly) waking up") {
    for {
      q <- AsyncQueue.unbounded[String].run[F]
      fib1 <- q.deque[F, String].start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.deque[F, String].start
      _ <- this.tickAll // add a second waiter
      _ <- q.enqueue("foo") // this will wake up `fib1`, but:
      _ <- fib1.cancel // we cancel it
      // (depending on which task the ticked runtime runs first, it is either cancelled, or completed)
      _ <- this.tickAll // `fib1` either completes, or cancelled
      oc <- fib1.join
      _ <- oc match {
        case Outcome.Canceled() =>
          // `fib1` was cancelled, it must wake up `fib2` instead of itself;
          // if it didn't (which is a bug), this will hang:
          assertResultF(fib2.joinWithNever, "foo")
        case Outcome.Succeeded(fa) =>
          // `fib1` completed, so it must have the item:
          assertResultF(fa, "foo") *> fib2.cancel
        case Outcome.Errored(ex) =>
          failF(ex.toString)
      }
    } yield ()
  }

  test("AsyncQueue.synchronous".fail) {
    object Cancelled extends Exception
    for {
      q <- AsyncQueue.synchronous[Int].run[F]
      _ <- assertResultF(q.tryEnqueue[F](1), false)
      _ <- assertResultF(q.tryDeque.run[F], None)
      f1 <- q.enqueue(2).attempt.start
      _ <- this.tickAll
      _ <- F.sleep(1.second)
      _ <- f1.cancel
      _ <- assertResultF(f1.joinWith(onCancel = F.pure(Left(Cancelled))), Left(Cancelled))
      f2 <- q.deque.attempt.start
      _ <- this.tickAll
      _ <- F.sleep(1.second)
      _ <- f2.cancel
      _ <- assertResultF(f2.joinWith(onCancel = F.pure(Left(Cancelled))), Left(Cancelled))
      f3 <- q.enqueue(3).start
      _ <- this.tickAll
      _ <- assertResultF(q.tryDeque.run[F], Some(3))
      _ <- f3.joinWithNever
      f4 <- q.deque.start
      _ <- this.tickAll
      f5 <- q.deque.start
      _ <- this.tickAll
      _ <- q.enqueue(4)
      _ <- assertResultF(f4.joinWithNever, 4)
      _ <- q.enqueue(5)
      _ <- assertResultF(f5.joinWithNever, 5)
      _ <- assertResultF(q.tryEnqueue[F](42), false)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("AsyncQueue.synchronous both empty and full".fail) {
    for {
      q <- AsyncQueue.synchronous[Int].run[F]
      // a setter is waiting:
      f1 <- q.enqueue(1).start
      _ <- this.tickAll
      // tryGet must complete it:
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- assertResultF(f1.joinWithNever, ())
      // another setter is waiting:
      f2 <- q.enqueue(2).start
      _ <- this.tickAll
      // asyncGet must complete it:
      _ <- assertResultF(q.deque, 2)
      _ <- assertResultF(f2.joinWithNever, ())
      // a getter is waiting:
      f3 <- q.deque.start
      _ <- this.tickAll
      // trySet must complete it:
      _ <- assertResultF(q.tryEnqueue.apply[F](3), true)
      _ <- assertResultF(f3.joinWithNever, 3)
      // another getter is waiting:
      f4 <- q.deque.start
      _ <- this.tickAll
      // asyncSet must complete it:
      _ <- assertResultF(q.enqueue(4), ())
      _ <- assertResultF(f4.joinWithNever, 4)
    } yield ()
  }
}
