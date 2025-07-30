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

import core.{ Rxn, Ref, AsyncReactive }

final class WaitListSpec_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with WaitListSpec[IO]

trait WaitListSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  test("WaitList around a Ref") {
    for {
      ref <- Ref[Option[Int]](None).run[F]
      wl <- WaitList[Int](
        ref.get,
        i => ref.getAndSet(Some(i)).void
      ).run[F]
      f1 <- wl.asyncGet.start
      _ <- this.tickAll
      f2 <- wl.asyncGet.start
      _ <- this.tickAll
      _ <- wl.set(42).run[F]
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- wl.set(21).run[F]
      _ <- assertResultF(f2.joinWithNever, 21)
    } yield ()
  }

  commonTests("WaitList", AsyncQueue.unbounded[String].run[F].widen)
  commonTests("GenWaitList", AsyncQueue.bounded[String](8).run[F].widen)

  private def commonTests(topts: TestOptions, newQueue: F[AsyncQueue.Take[String] & data.Queue.Offer[String]]): Unit = {

    test(topts.withName(s"${topts.name}: deque and poll race")) {
      for {
        q <- newQueue
        fib1 <- q.take[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        fib2 <- q.take[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        // to be fair(er), the item should be received by the suspended fiber, and NOT `poll`
        _ <- assertResultF(F.both(q.poll.run[F], q.offer("foo").run[F]), (None, true))
        _ <- assertResultF(fib1.joinWithNever, "foo")
        _ <- fib2.cancel
      } yield ()
    }

    test(topts.withName(s"${topts.name}: deque wakes up, then goes to sleep again")) {
      for {
        q <- newQueue
        fib1 <- q.take[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        _ <- assertResultF(q.offer("foo").run[F], true) // this will wake up the fiber, but:
        maybeResult <- q.poll.run[F] // this has a chance of overtaking the fiber
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
        fib1 <- q.take[F, String].start
        _ <- this.tickAll // wait for fiber to suspend
        fib2 <- q.take[F, String].start
        _ <- this.tickAll // add a second waiter
        _ <- assertResultF(q.offer("foo").run[F], true) // this will wake up `fib1`, but:
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

  test("GenWaitList: enqueue and offer race") {
    for {
      q <- AsyncQueue.bounded[String](1).run[F]
      _ <- assertResultF(q.offer("first").run[F], true) // fill the queue
      fib1 <- q.put[F]("foo").start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.put[F]("bar").start
      _ <- this.tickAll // wait for fiber to suspend
      // to be fair(er), the suspended fiber should be able to insert its item, and NOT `offer`
      _ <- assertResultF(F.both(q.offer("xyz").run[F], q.poll.run[F]), (false, Some("first")))
      _ <- assertResultF(fib1.joinWithNever, ())
      _ <- fib2.cancel
      _ <- this.tickAll
      _ <- assertResultF(q.poll.run[F], Some("foo"))
    } yield ()
  }

  test("GenWaitList: enqueue wakes up, then goes to sleep again") {
    for {
      q <- AsyncQueue.bounded[String](1).run[F]
      _ <- assertResultF(q.offer("first").run[F], true) // fill the queue
      fib1 <- q.put[F]("foo").start
      _ <- this.tickAll // wait for fiber to suspend
      _ <- assertResultF(q.poll.run[F], Some("first")) // this will wake up the fiber, but:
      succ <- q.offer("bar").run[F] // this has a chance of overtaking the fiber
      // (depending on which task the ticked runtime runs first)
      _ <- this.tickAll // fiber either completes, or goes back to sleep
      _ <- if (succ) {
        // fiber lost, it is sleeping now
        fib1.cancel *> { // this will hang if it's uncancelable (which is a bug)
          assertResultF(q.take[F, String], "bar")
        }
      } else {
        // fiber won, it's done now
        assertResultF(fib1.joinWithNever, ()) *> assertResultF(q.take[F, String], "foo")
      }
    } yield ()
  }

  test("GenWaitList: enqueue gets cancelled right after (correctly) waking up") {
    for {
      q <- AsyncQueue.bounded[String](1).run[F]
      _ <- assertResultF(q.put[F]("first"), ()) // fill the queue
      fib1 <- q.put[F]("foo").start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.put[F]("bar").start
      _ <- this.tickAll // add a second waiter
      _ <- assertResultF(q.poll.run[F], Some("first")) // this will wake up `fib1`, but:
      _ <- fib1.cancel // we cancel it
      // (depending on which task the ticked runtime runs first, it is either cancelled, or completed)
      _ <- this.tickAll // `fib1` either completes, or cancelled
      oc <- fib1.join
      _ <- oc match {
        case Outcome.Canceled() =>
          // `fib1` was cancelled, it must wake up `fib2` instead of itself;
          // if it didn't (which is a bug), this will hang:
          assertResultF(fib2.joinWithNever, ()) *> assertResultF(q.poll.run[F], Some("bar"))
        case Outcome.Succeeded(fa) =>
          // `fib1` completed, so the other one should still be suspended
          assertResultF(fa, ()) *> fib2.cancel *> assertResultF(fib2.join, Outcome.canceled[F, Throwable, Unit])
        case Outcome.Errored(ex) =>
          failF(ex.toString)
      }
    } yield ()
  }

  private sealed trait DebugQueue[A]
    extends AsyncQueue.UnsealedAsyncQueueTake[A]
    with data.Queue.UnsealedQueueOffer[A] {
    def tryGetUnderlyingCount: Rxn[Int]
    def setUnderlyingCount: Rxn[Int]
  }

  private[this] final def debugAsyncQueue[A](
    underlying: data.Queue[A]
  ): Rxn[DebugQueue[A]] = {
    WaitList.debug[A](
      tryGetUnderlying = underlying.poll,
      setUnderlying = underlying.add,
    ).map { wl =>
      new DebugQueue[A] {
        final override def tryGetUnderlyingCount: Rxn[Int] = wl.tryGetUnderlyingCount
        final override def setUnderlyingCount: Rxn[Int] = wl.setUnderlyingCount
        final override def offer(a: A): Rxn[Boolean] = wl.set(a).as(true)
        final override def poll: Rxn[Option[A]] = wl.tryGet
        final override def take[G[_], AA >: A](implicit G: AsyncReactive[G]): G[AA] = G.monad.widen(wl.asyncGet(using G))
      }
    }
  }

  test("WaitList#asyncGet should have no unnecessary wakeups") {
    val initialCount = 1
    val t = for {
      ul <- data.Queue.unbounded[String].run[F]
      q <- debugAsyncQueue[String](ul).run[F]
      holder <- F.ref[String](null)
      fib1 <- F.uncancelable { poll =>
        poll(q.take[F, String]).flatMap(holder.set)
      }.start
      _ <- this.tickAll // wait for fiber to suspend
      _ <- assertResultF(q.tryGetUnderlyingCount.run, initialCount)
      fib2 <- q.take[F, String].start
      _ <- this.tickAll // add a second waiter
      _ <- assertResultF(q.tryGetUnderlyingCount.run, initialCount)
      fib3 <- q.take[F, String].start
      _ <- this.tickAll // add a third waiter
      _ <- assertResultF(q.tryGetUnderlyingCount.run, initialCount)
      _ <- F.both(q.offer("foo").run, fib1.cancel)
      _ <- this.tickAll
      oc <- fib1.join
      holderVal <- holder.get
      _ <- if (holderVal eq null) {
        for {
          _ <- assertF(oc.isCanceled)
          _ <- assertResultF(fib2.joinWithNever, "foo")
        } yield ()
      } else {
        for {
          _ <- assertF(oc.isSuccess)
          _ <- assertEqualsF(holderVal, "foo")
          oc2 <- fib2.cancel *> fib2.join
          _ <- assertF(oc2.isCanceled)
        } yield ()
      }
      oc3 <- fib3.cancel *> fib3.join
      _ <- assertF(oc3.isCanceled)
      _ <- assertResultF(q.tryGetUnderlyingCount.run, initialCount + 1)
    } yield ()
    t.replicateA_(if (isJvm()) 50 else 5)
  }
}
