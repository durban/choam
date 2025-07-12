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

import cats.effect.kernel.{ Ref, Deferred }
import cats.effect.IO
import cats.effect.instances.spawn._

import munit.TestOptions

final class WaitListSpecPar_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with WaitListSpecPar[IO]

// Note: these tests can fail unexpectedly with ZIO
// due to https://github.com/zio/zio/issues/9974.
//
// final class WaitListSpecPar_DefaultMcas_ZIO
//   extends BaseSpecZIO
//   with SpecDefaultMcas
//   with WaitListSpecPar[zio.Task]

trait WaitListSpecPar[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  private val common = List[(TestOptions, F[AsyncQueueSource[String] & BoundedQueueSink[String]])](
    ("AsyncQueue.unbounded", AsyncQueue.unbounded[String].run[F].widen),
    ("AsyncQueue.unboundedWithSize", AsyncQueue.unboundedWithSize[String].run[F].widen),
    ("AsyncQueue.bounded", AsyncQueue.bounded[String](42).run[F].widen),
    ("BoundedQueue.linked", BoundedQueue.linked[String](42).run[F].widen),
    ("AsyncQueue.dropping", AsyncQueue.dropping[String](42).run[F].widen),
    ("AsyncQueue.ringBuffer", AsyncQueue.ringBuffer[String](42).run[F].widen),
  )

  for ((testOpts, newEmptyQ) <- common) {
    testDequeCancel(testOpts, newEmptyQ)
    testDequeAndPollRace(testOpts, newEmptyQ)
  }

  private def deqAndSave(q: AsyncQueueSource[String], ref: Ref[F, String]): F[Unit] = {
    F.uncancelable { poll =>
      poll(q.deque).flatMap { s =>
        // if deque completes, we will certainly save the item:
        ref.set(s)
      }
    }
  }

  private def testDequeCancel(
    testOpts: TestOptions,
    newEmptyQ: F[AsyncQueueSource[String] & BoundedQueueSink[String]],
  ): Unit = {
    test(testOpts.withName(s"${testOpts.name}: deque cancel race")) {
      val t = for {
        q <- newEmptyQ
        r <- F.ref("")
        fib <- deqAndSave(q, r).start
        _ <- F.sleep(0.01.seconds)
        _ <- F.both(fib.cancel, q.enqueueAsync("foo"))
        _ <- fib.join
        s <- r.get
        _ <- if (s.nonEmpty) { // deque completed
          assertEqualsF(s, "foo") *> assertResultF(q.poll.run[F], None, "item is duplicated")
        } else { // deque cancelled
          assertResultF(q.poll.run[F], Some("foo"), "item is lost")
        }
      } yield ()
      t.parReplicateA_(100)
    }
  }

  private def testDequeAndPollRace(
    testOpts: TestOptions,
    newEmptyQ: F[AsyncQueueSource[String] & BoundedQueueSink[String]],
  ): Unit = {
    test(testOpts.withName(s"${testOpts.name}: deque and poll race")) {
      val t = for {
        q <- newEmptyQ
        fib1 <- q.deque[F, String].start
        fib2 <- q.deque[F, String].start
        _ <- F.sleep(1.seconds) // wait for fibers to suspend
        // to be fair(er), the item should be received by the suspended fiber, and NOT `poll`
        _ <- assertResultF(F.both(F.cede *> q.poll.run[F], q.enqueueAsync("foo")), (None, ()))
        // ok, now unblock one of the fibers (the other one is already unblocked, but we don't know which):
        _ <- q.enqueueAsync("bar")
        item1 <- fib1.joinWithNever
        item2 <- fib2.joinWithNever
        _ <- assertEqualsF(Set(item1, item2), Set("foo", "bar"))
      } yield ()
      t.parReplicateA_(100)
    }
  }

  testEnqueueCancelBounded("AsyncQueue.bounded", AsyncQueue.bounded[String](42).run[F].widen, bounds = List(1, 8))
  testEnqueueCancelBounded("BoundedQueue.linked", BoundedQueue.linked[String](42).run[F].widen, bounds = List(1, 8))

  private def testEnqueueCancelBounded(
    name: String,
    newEmptyQ: F[AsyncQueueSource[String] & BoundedQueueSink[String]],
    bounds: List[Int],
  ): Unit = {
    for (bound <- bounds) {
      _testEnqueueCancelBounded(name, newEmptyQ, bound)
    }
  }

  private def _testEnqueueCancelBounded(
    name: String,
    newEmptyQ: F[AsyncQueueSource[String] & BoundedQueueSink[String]],
    bound: Int,
  ): Unit = {

    def enqAndSave(q: BoundedQueueSink[String], ref: Ref[F, Boolean], item: String): F[Unit] = {
      F.uncancelable { poll =>
        poll(q.enqueueAsync(item)).flatMap { _ =>
          // if enqueue completes, we will certainly set it to true:
          ref.set(true)
        }
      }
    }

    def deqIntoDeferred(q: AsyncQueueSource[String], d: Deferred[F, String]): F[Unit] = {
      F.uncancelable { poll =>
        poll(q.deque[F, String]).flatMap { s =>
          d.complete(s).flatMap { ok =>
            if (ok) F.unit
            else F.raiseError(new AssertionError)
          }
        }
      }
    }

    test(s"$name: enqueue cancel race (1 waiter; bound = $bound)") {
      val t = for {
        _ <- assertF(bound > 0)
        q <- newEmptyQ
        _ <- (1 to bound).toList.traverse_(i => q.enqueueAsync(i.toString)) // make it full
        r <- F.ref(false)
        d <- F.deferred[String]
        enqFib <- enqAndSave(q, r, "foo").start
        _ <- F.sleep(0.01.seconds)
        _ <- F.both(enqFib.cancel, deqIntoDeferred(q, d).start)
        _ <- enqFib.join
        flag <- r.get
        _ <- assertResultF(d.get, "1")
        _ <- (2 to bound).toList.traverse_(i => assertResultF(q.deque, i.toString))
        _ <- if (flag) { // enqueue completed
          assertResultF(q.poll.run[F], Some("foo"), "item lost")
        } else { // enqueue cancelled
          assertResultF(q.poll.run[F], None, "item duplicated")
        }
      } yield ()
      t.parReplicateA_(50000)
    }

    test(s"$name: enqueue cancel race (2 waiters; bound = $bound)") {
      val t = for {
        _ <- assertF(bound > 0)
        q <- newEmptyQ
        _ <- (1 to bound).toList.traverse_(i => q.enqueueAsync(i.toString)) // make it full
        r1 <- F.ref(false)
        r2 <- F.ref(false)
        d <- F.deferred[String]
        enqFib1 <- enqAndSave(q, r1, "foo1").start
        enqFib2 <- enqAndSave(q, r2, "foo2").start
        _ <- F.sleep(0.01.seconds)
        _ <- F.both(enqFib1.cancel, deqIntoDeferred(q, d).start)
        _ <- enqFib1.join
        flag <- r1.get
        _ <- assertResultF(d.get, "1")
        _ <- (2 to bound).toList.traverse_(i => assertResultF(q.deque, i.toString))
        _ <- if (flag) { // enqueue1 completed
          q.deque.flatMap { item =>
            if (item == "foo1") { // enqueue1 won
              assertResultF(q.deque, "foo2")
            } else { // enqueue2 won
              assertEqualsF(item, "foo2") *> assertResultF(q.deque, "foo1")
            }
          } *> enqFib2.joinWithNever
        } else { // enqueue1 cancelled
          assertResultF(q.deque, "foo2") *> enqFib2.joinWithNever
        }
      } yield ()
      t.parReplicateA_(50000)
    }
  }
}
