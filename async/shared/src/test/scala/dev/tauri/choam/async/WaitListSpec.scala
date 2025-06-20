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

import cats.effect.{ IO, Outcome }

import munit.TestOptions

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

  commonTests("WaitList", AsyncQueue.unbounded[String].run[F].widen)
  commonTests("GenWaitList", AsyncQueue.bounded[String](8).run[F].widen)

  private def commonTests(topts: TestOptions, newQueue: F[AsyncQueueSource[String] & data.QueueSink[String]]): Unit = {

    test(topts.withName(s"${topts.name}: deque and tryDeque race")) {
      for {
        q <- newQueue
        fib1 <- q.deque[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        fib2 <- q.deque[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        // to be fair(er), the item should be received by the suspended fiber, and NOT `tryDeque`
        _ <- assertResultF(F.both(q.tryDeque.run[F], q.tryEnqueue[F]("foo")), (None, true))
        _ <- assertResultF(fib1.joinWithNever, "foo")
        _ <- fib2.cancel
      } yield ()
    }

    test(topts.withName(s"${topts.name}: deque wakes up, then goes to sleep again")) {
      for {
        q <- newQueue
        fib1 <- q.deque[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        _ <- assertResultF(q.tryEnqueue[F]("foo"), true) // this will wake up the fiber, but:
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

    test(topts.withName(s"${topts.name}: deque gets cancelled right after (correctly) waking up")) {
      for {
        q <- newQueue
        fib1 <- q.deque[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        fib2 <- q.deque[F, String].start
        _ <- this.tickAll // add a second waiter
        _ <- assertResultF(q.tryEnqueue[F]("foo"), true) // this will wake up `fib1`, but:
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
            assertResultF(fa, "foo") *> fib2.cancel *> assertResultF(fib2.join, Outcome.canceled[F, Throwable, String])
          case Outcome.Errored(ex) =>
            failF(ex.toString)
        }
      } yield ()
    }
  }

  test("GenWaitList: enqueue and tryEnqueue race") {
    for {
      q <- AsyncQueue.bounded[String](1).run[F]
      _ <- assertResultF(q.tryEnqueue[F]("first"), true) // fill the queue
      fib1 <- q.enqueue[F]("foo").start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.enqueue[F]("bar").start
      _ <- this.tickAll // wait for fiber to suspend
      // to be fair(er), the suspended fiber should be able to insert its item, and NOT `tryEnqueue`
      _ <- assertResultF(F.both(q.tryEnqueue[F]("xyz"), q.tryDeque.run[F]), (false, Some("first")))
      _ <- assertResultF(fib1.joinWithNever, ())
      _ <- fib2.cancel
      _ <- this.tickAll
      _ <- assertResultF(q.tryDeque.run[F], Some("foo"))
    } yield ()
  }

  test("GenWaitList: enqueue wakes up, then goes to sleep again") {
    for {
      q <- AsyncQueue.bounded[String](1).run[F]
      _ <- assertResultF(q.tryEnqueue[F]("first"), true) // fill the queue
      fib1 <- q.enqueue[F]("foo").start
      _ <- this.tickAll // wait for fiber to suspend
      _ <- assertResultF(q.tryDeque.run[F], Some("first")) // this will wake up the fiber, but:
      succ <- q.tryEnqueue[F]("bar") // this has a chance of overtaking the fiber
      // (depending on which task the ticked runtime runs first)
      _ <- this.tickAll // fiber either completes, or goes back to sleep
      _ <- if (succ) {
        // fiber lost, it is sleeping now
        fib1.cancel *> { // this will hang if it's uncancelable (which is a bug)
          assertResultF(q.deque[F, String], "bar")
        }
      } else {
        // fiber won, it's done now
        assertResultF(fib1.joinWithNever, ()) *> assertResultF(q.deque[F, String], "foo")
      }
    } yield ()
  }

  test("GenWaitList: enqueue gets cancelled right after (correctly) waking up") {
    for {
      q <- AsyncQueue.bounded[String](1).run[F]
      _ <- assertResultF(q.enqueue[F]("first"), ()) // fill the queue
      fib1 <- q.enqueue[F]("foo").start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.enqueue[F]("bar").start
      _ <- this.tickAll // add a second waiter
      _ <- assertResultF(q.tryDeque.run[F], Some("first")) // this will wake up `fib1`, but:
      _ <- fib1.cancel // we cancel it
      // (depending on which task the ticked runtime runs first, it is either cancelled, or completed)
      _ <- this.tickAll // `fib1` either completes, or cancelled
      oc <- fib1.join
      _ <- oc match {
        case Outcome.Canceled() =>
          // `fib1` was cancelled, it must wake up `fib2` instead of itself;
          // if it didn't (which is a bug), this will hang:
          assertResultF(fib2.joinWithNever, ()) *> assertResultF(q.tryDeque.run[F], Some("bar"))
        case Outcome.Succeeded(fa) =>
          // `fib1` completed, so the other one should still be suspended
          assertResultF(fa, ()) *> fib2.cancel *> assertResultF(fib2.join, Outcome.canceled[F, Throwable, Unit])
        case Outcome.Errored(ex) =>
          failF(ex.toString)
      }
    } yield ()
  }
}
