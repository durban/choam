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

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import cats.effect.kernel.{ Ref => CatsRef }
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

  test("WaitList#asyncSetCb") {
    for {
      ref <- Ref[Option[Int]](None).run[F]
      wl <- WaitList[Int](
        ref.get,
        i => ref.getAndSet(Some(i)).void
      ).run[F]
      flag <- GenWaitList.Flag.mkNew(true).run
      _ <- F.asyncCheckAttempt[Unit] { cb =>
        wl.asyncSetCb(42, cb, firstTry = true, flag = flag).run[F].widen
      }
      _ <- assertResultF(ref.get.run, Some(42))
    } yield ()
  }

  test("GenWaitList#asyncSetCb") {
    for {
      ref <- Ref[Option[Int]](None).run[F]
      gwl <- GenWaitList[Int](
        ref.getAndUpdate { _ => None },
        i => ref.modify {
          case None => (Some(i), true)
          case s => (s, false)
        }
      ).run[F]
      flag0 <- GenWaitList.Flag.mkNew(true).run
      _ <- F.asyncCheckAttempt[Unit] { cb =>
        gwl.asyncSetCb(42, cb, firstTry = true, flag = flag0).map {
          case Left(fin) => Left(Some(fin.run[F]): Option[F[Unit]])
          case Right(()) => Right(())
        }.run[F]
      }
      _ <- assertResultF(ref.get.run, Some(42))
      d <- F.deferred[Unit]
      flag1 <- GenWaitList.Flag.mkNew(true).run
      fib <- F.asyncCheckAttempt[Unit] { cb =>
        gwl.asyncSetCb(21, cb, firstTry = true, flag = flag1).map {
          case Left(fin) => Left(Some(fin.run[F]): Option[F[Unit]])
          case Right(()) => Right(())
        }.run[F]
      }.guarantee(d.complete(()).void).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- assertResultF(gwl.asyncGet[F], 42)
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, Some(()))
      _ <- assertResultF(fib.joinWithNever, ())
      flag2 <- GenWaitList.Flag.mkNew(false).run
      _ <- F.asyncCheckAttempt[Unit] { cb =>
        gwl.asyncSetCb(21, cb, firstTry = false, flag = flag2).map {
          case Left(fin) => Left(Some(fin.run[F]): Option[F[Unit]])
          case Right(()) => Right(())
        }.run[F]
      }
      _ <- assertResultF(ref.get.run, Some(21))
    } yield ()
  }

  cancelStressTest[AsyncQueue[String]]("WaitList (put)", AsyncQueue.unbounded[String].run[F], { (q, s) => q.put(s).as(true) })
  cancelStressTest[AsyncQueue.SourceSink[String]]("GenWaitList (put)", AsyncQueue.bounded[String](8).run[F], { (q, s) => q.put(s).as(true) })

  commonTests("WaitList", AsyncQueue.unbounded[String].run[F].widen)
  commonTests("GenWaitList", AsyncQueue.bounded[String](8).run[F].widen)

  private def commonTests(topts: TestOptions, newQueue: F[AsyncQueue.Take[String] & data.Queue.Offer[String]]): Unit = {

    type Q = AsyncQueue.Take[String] & data.Queue.Offer[String]

    cancelStressTest[Q](topts.withName(topts.name + " (offer)"), newQueue, { (q, s) => q.offer(s).run[F] })

    test(topts.withName(s"${topts.name}: deque and poll race")) {
      val t = for {
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
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(topts.withName(s"${topts.name}: deque wakes up, then goes to sleep again")) {
      val t = for {
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
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(topts.withName(s"${topts.name}: deque gets cancelled right after (correctly) waking up")) {
      val t = for {
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
      t.replicateA_(if (isJvm()) 50 else 5)
    }
  }

  private def cancelStressTest[Q <: AsyncQueue.Take[String]](
    topts: TestOptions,
    newQueue: F[Q],
    offer: (Q, String) => F[Boolean],
  ): Unit = {

    test(topts.withName(s"${topts.name}: lots of cancels")) {
      val N = 64
      val M = 2 * N
      val t = for {
        q <- newQueue
        rng <- F.delay(new scala.util.Random(ThreadLocalRandom.current().nextLong()))
        killSwitch <- F.ref(false)
        rr <- F.both(
          (1 to N).toList.traverse { _ =>
            F.ref[String]("").flatMap { holder =>
              F.uncancelable { poll =>
                poll(q.take[F, String]).flatMap(holder.set)
              }.start.map { fib => (fib, holder) }
            }
          },
          F.ref[Int](0).flatMap { sRef =>
            (1 to M).toList.traverse { idx =>

              def once(holder: CatsRef[F, String], successes: CatsRef[F, Int]): F[Unit] = {
                F.uncancelable { poll =>
                  poll(offer(q, idx.toString)).flatTap { ok =>
                    if (ok) holder.set(idx.toString) *> successes.update(_ + 1)
                    else F.unit
                  }
                }.flatMap { ok =>
                  if (ok) {
                    F.unit
                  } else {
                    (successes.get, killSwitch.get).flatMapN { (succ, killed) =>
                      if ((succ >= N) || killed) {
                        F.unit
                      } else {
                        F.sleep(1.second) *> once(holder, successes)
                      }
                    }
                  }
                }
              }

              F.ref[String]("").flatMap { holder =>
                (once(holder, sRef).start <* F.cede).map { fib => (fib, holder) }
              }
            }
          }
        )
        (takeFibers, offerFibers) = rr
        _ <- F.both(
          rng.shuffle(takeFibers).take(N >> 1).traverse { case (fib, _) => fib.cancel.start }.flatMap(_.traverse_(_.join)),
          rng.shuffle(offerFibers).take(M >> 1).traverse { case (fib, _) => fib.cancel.start }.flatMap(_.traverse_(_.join)),
        )
        taken <- takeFibers.traverse { case (fib, holder) =>
          fib.join.flatMap[Option[String]] { _ =>
            holder.get.map {
              case "" => None
              case s => Some(s)
            }
          }
        }.map(_.collect { case Some(s) => s })
        _ <- killSwitch.set(true)
        // TODO: do we need killSwitch if we're cancelling every offerer anyway:
        _ <- offerFibers.traverse { case (fib, _) => fib.cancel.start }.flatMap(_.traverse_(_.join))
        offered <- offerFibers.traverse { case (fib, holder) =>
          fib.join.flatMap[Option[String]] { _ =>
            holder.get.map {
              case "" => None
              case s => Some(s)
            }
          }
        }.map(_.collect { case Some(s) => s })
        drained <- data.Queue.drainOnce(q)
        _ <- assertEqualsF((clue(taken) ++ clue(drained)).sorted, clue(offered).sorted)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }
  }

  test("GenWaitList: enqueue and offer race") {
    val t = for {
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
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  test("GenWaitList: enqueue wakes up, then goes to sleep again") {
    val t = for {
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
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  test("GenWaitList: enqueue gets cancelled right after (correctly) waking up") {
    val t = for {
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
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  test("GenWaitList: underlying queue is full, but there are getters") {
    val t = for {
      q <- AsyncQueue.bounded[String](2).run[F]
      fib1 <- q.take.start
      _ <- this.tickAll // wait for fiber to suspend
      fib2 <- q.take.start
      _ <- this.tickAll // add a second waiter
      fib3 <- q.take.start
      _ <- this.tickAll // add a third waiter
      // if these `offer`s happen sufficiently
      // fast, it can happen that there is still
      // a waiter (fib3), but the underlying
      // queue is already full; if that happens,
      // the result must be (true, true, false);
      // otherwise (the normal case): (true, true, true).
      rrr <- (q.offer("foo").run, q.offer("bar").run, q.offer("baz").run).tupled
      _ <- assertEqualsF((rrr._1, rrr._2), (true, true))
      // due to the fibers racing when waking up, the order might not be strict:
      _ <- if (rrr._3) {
        // all 3 succeeded:
        for {
          v1 <- fib1.joinWithNever
          v2 <- fib2.joinWithNever
          v3 <- fib3.joinWithNever
          _ <- assertEqualsF(Set(v1, v2, v3), Set("foo", "bar", "baz"))
        } yield ()
      } else {
        for {
          v1 <- fib1.joinWithNever
          v2 <- fib2.joinWithNever
          _ <- assertEqualsF(Set(v1, v2), Set("foo", "bar"))
          _ <- fib3.cancel
          _ <- assertResultF(fib3.join.map(_.isCanceled), true)
        } yield ()
      }
    } yield ()
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  noUnneededWakeupForAsyncGet("WaitList", bound = None)
  noUnneededWakeupForAsyncGet("GenWaitList", bound = Some(8))

  noUnneededWakeupForAsyncSet("GenWaitList", bound = 8)

  private def noUnneededWakeupForAsyncSet(topts: TestOptions, bound: Int): Unit = {

    test(topts.withName(s"${topts.name}: asyncSet should have no unnecessary wakeups")) {
      require(bound > 0)
      val t = for {
        q <- WaitListSpec.debugQueue[String](bound = Some(bound)).run[F]
        _ <- (1 to bound).toList.traverse_(i => q.put[F](i.toString)) // make it full
        initialCount <- q.trySetUnderlyingCount.run
        holder <- F.ref[Boolean](false)
        fib1 <- F.uncancelable { poll =>
          poll(q.put[F]("foo")).flatMap(_ => holder.set(true))
        }.start
        _ <- this.tickAll // wait for fiber to suspend
        fib2 <- q.put[F]("abc").start
        _ <- this.tickAll // add a second waiter
        fib3 <- q.put[F]("def").start
        _ <- this.tickAll // add a third waiter
        _ <- assertResultF(q.trySetUnderlyingCount.run, initialCount + 1)
        rr <- F.both(q.take[F, String], fib1.cancel)
        _ <- assertEqualsF(rr._1, "1")
        _ <- this.tickAll
        oc <- fib1.join
        holderVal <- holder.get
        _ <- if (!holderVal) {
          for {
            _ <- assertF(oc.isCanceled)
            _ <- assertResultF(fib2.joinWithNever, ())
          } yield ()
        } else {
          for {
            _ <- assertF(oc.isSuccess)
            oc2 <- fib2.cancel *> fib2.join
            _ <- assertF(oc2.isCanceled)
          } yield ()
        }
        oc3 <- fib3.cancel *> fib3.join
        _ <- assertF(oc3.isCanceled)
        _ <- assertResultF(q.trySetUnderlyingCount.run, initialCount + 1 + 1)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }
  }

  private def noUnneededWakeupForAsyncGet(topts: TestOptions, bound: Option[Int]): Unit = {
    test(topts.withName(s"${topts.name}: asyncGet should have no unnecessary wakeups")) {
      val initialCount = 1
      val t = for {
        q <- WaitListSpec.debugQueue[String](bound = bound).run[F]
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
        rr <- F.both(q.offer("foo").run, fib1.cancel)
        _ <- assertEqualsF(rr._1, true)
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
}

object WaitListSpec {

  private[WaitListSpec] sealed trait DebugQueue[A]
    extends AsyncQueue.UnsealedAsyncQueueTake[A]
    with AsyncQueue.UnsealedAsyncQueuePut[A]
    with data.Queue.UnsealedQueueOffer[A] {
    def tryGetUnderlyingCount: Rxn[Int]
    def trySetUnderlyingCount: Rxn[Int]
  }

  private[WaitListSpec] final def debugQueue[A](
    bound: Option[Int]
  ): Rxn[DebugQueue[A]] = bound match {
    case Some(n) =>
      data.Queue.bounded[A](n).flatMap { underlying =>
        GenWaitList.debug[A](
          tryGetUnderlying = underlying.poll,
          trySetUnderlying = underlying.offer,
        ).flatMap(gwl => debugQueue(gwl))
      }
    case None =>
      data.Queue.unbounded[A].flatMap { underlying =>
        WaitList.debug[A](
          tryGetUnderlying = underlying.poll,
          setUnderlying = underlying.add,
        ).flatMap(wl => debugQueue(wl))
      }
  }

  private[this] final def debugQueue[A](
    wl: GenWaitList.Debug[A],
  ): Rxn[DebugQueue[A]] = {
    Rxn.pure(
      new DebugQueue[A] {
        final override def tryGetUnderlyingCount: Rxn[Int] = wl.tryGetUnderlyingCount
        final override def trySetUnderlyingCount: Rxn[Int] = wl.trySetUnderlyingCount
        final override def offer(a: A): Rxn[Boolean] = wl.trySet(a)
        final override def poll: Rxn[Option[A]] = wl.tryGet
        final override def take[G[_], AA >: A](implicit G: AsyncReactive[G]): G[AA] = G.monad.widen(wl.asyncGet(using G))
        final override def put[G[_]](a: A)(implicit G: AsyncReactive[G]): G[Unit] = wl.asyncSet[G](a)
      }
    )
  }
}
