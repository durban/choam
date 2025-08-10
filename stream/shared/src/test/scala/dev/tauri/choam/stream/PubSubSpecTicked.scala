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
package stream

import scala.concurrent.duration._

import cats.effect.kernel.Outcome
import fs2.Chunk

import core.{ Rxn, Ref }
import async.Promise

import PubSub.OverflowStrategy
import PubSub.OverflowStrategy.{ dropOldest, dropNewest, backpressure, unbounded }

trait PubSubSpecTicked[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected[this] type H[A] <: PubSub.Simple[A]

  protected[this] def newHub[A](str: PubSub.OverflowStrategy): F[H[A]]

  commonTests("DropOldest", dropOldest(64))
  droppingTests("DropOldest", dropOldest(4), 4)
  noBackpressureTests("DropOldest", dropOldest(64))
  singleElementBufferTests("DropOldest", dropOldest(1))

  test("DropOldest - should drop oldest elements") {
    val str = dropOldest(3)
    for {
      hub <- newHub[Int](str)
      fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
      slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
      withInit <- hub.subscribeWithInitial(str, Rxn.pure(0)).evalTap(_ => F.sleep(0.5.second)).compile.toVector.start
      _ <- this.tickAll // wait for subscriptions to happen
      _ <- assertResultF(hub.emit(1).run, PubSub.Success)
      _ <- this.tick // make sure they receive the 1st, and then start to sleep
      _ <- assertResultF(hub.emitChunk(Chunk(2, 3)).run, PubSub.Success)
      _ <- assertResultF(hub.emit(4).run, PubSub.Success) // buffers full
      _ <- assertResultF(hub.emit(5).run, PubSub.Success)
      _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
      _ <- assertResultF(hub.emit(6).run, PubSub.Success)
      _ <- assertResultF(hub.close.run, PubSub.Backpressured)
      _ <- assertResultF(fast.joinWithNever, Vector(1, 3, 4, 5, 6))
      _ <- assertResultF(slow.joinWithNever, Vector(1, 4, 5, 6))
      _ <- assertResultF(withInit.joinWithNever, Vector(0, 1, 4, 5, 6))
    } yield ()
  }

  test("DropOldest - size computation") {
    val str = dropOldest(1)
    val t = for {
      hub <- newHub[Int](str)
      rr <- F.both(
        hub.subscribeWithInitial(str, Rxn.pure(0)).compile.toVector.start,
        hub.emit(1).run *> hub.emit(2).run,
      )
      (fib, _) = rr
      _ <- this.tickAll
      _ <- hub.close.run[F]
      r <- fib.joinWithNever
      _ <- assertF(
        (r == Vector(0)) || (r == Vector(0, 1)) || (r == Vector(0, 2)) || (r == Vector(0, 1, 2)),
        s"unexpected result: ${r}"
      )
    } yield ()
    t.replicateA_(if (isJvm()) 500 else 10)
  }

  commonTests("DropNewest", dropNewest(64))
  droppingTests("DropNewest", dropNewest(4), 4)
  noBackpressureTests("DropNewest", dropNewest(64))
  singleElementBufferTests("DropNewest", dropNewest(1))

  test("DropNewest - should drop newest elements") {
    val t = for {
      hub <- newHub[Int](dropNewest(3))
      fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
      slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
      _ <- this.tickAll // wait for subscriptions to happen
      _ <- assertResultF(hub.emit(1).run, PubSub.Success)
      _ <- this.tick // make sure they receive the 1st, and then start to sleep
      _ <- assertResultF(hub.emitChunk(Chunk(2, 3)).run, PubSub.Success)
      _ <- assertResultF(hub.emit(4).run, PubSub.Success) // buffers full
      _ <- assertResultF(hub.emit(5).run, PubSub.Success) // this is dropped
      _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
      _ <- assertResultF(hub.emitChunk(Chunk(6, 7)).run, PubSub.Success) // this is dropped
      _ <- assertResultF(hub.close.run, PubSub.Backpressured)
      _ <- assertResultF(fast.joinWithNever, Vector(1, 2, 3, 4, 6, 7))
      _ <- assertResultF(slow.joinWithNever, Vector(1, 2, 3, 4))
    } yield ()
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  commonTests("Unbounded", unbounded)
  noBackpressureTests("Unbounded", unbounded)

  commonTests("Backpressure", backpressure(64))
  singleElementBufferTests("Backpressure", backpressure(1))

  test("Backpressure - backpressuring") {
    val t = for {
      hub <- newHub[Int](backpressure(3))
      fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
      slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
      _ <- this.tickAll // wait for subscriptions to happen
      _ <- assertResultF(hub.emit(1).run, PubSub.Success)
      _ <- this.tick // make sure they receive the 1st, and then start to sleep
      _ <- assertResultF(hub.emitChunk(Chunk(2, 3)).run, PubSub.Success)
      _ <- assertResultF(hub.emit(4).run, PubSub.Success) // buffers full
      _ <- assertResultF(hub.emit(5).run, PubSub.Backpressured) // can't publish this
      _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
      _ <- assertResultF(hub.emit(6).run, PubSub.Backpressured) // still can't (all or nothing)
      _ <- this.tickAll // consume items
      _ <- assertResultF(hub.emitChunk(Chunk(10, 11, 12)).run, PubSub.Success)
      _ <- assertResultF(hub.close.run, PubSub.Backpressured)
      _ <- assertResultF(fast.joinWithNever, Vector(1, 2, 3, 4, 10, 11, 12))
      _ <- assertResultF(slow.joinWithNever, Vector(1, 2, 3, 4, 10, 11, 12))
    } yield ()
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  private def commonTests(name: String, str: OverflowStrategy): Unit = {

    test(s"$name - basics") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.emit(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.emitChunk(Chunk(2, 3)).run[F], PubSub.Success)
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.emit(4).run[F], PubSub.Success)
        _ <- assertResultF(hub.emitChunk(Chunk(5, 6)).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(f1.joinWithNever, Vector(1, 2, 3, 4, 5, 6))
        _ <- assertResultF(f3.joinWithNever, Vector(2, 3, 4, 5, 6, 7))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - closing") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.emit(1).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
        _ <- assertResultF(hub.emit(2).run[F], PubSub.Closed)
        _ <- assertResultF(hub.emitChunk(Chunk(2)).run[F], PubSub.Closed)
        _ <- assertResultF(f1.joinWithNever, Vector(1))
        _ <- assertResultF(f2.joinWithNever, Vector(1))
        _ <- assertResultF(f3.joinWithNever, Vector(2))
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - closing without subscribers") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.take(1).compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.emit(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.emitChunk(Chunk(2, 3)).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Success)
        _ <- assertResultF(f1.joinWithNever, Vector(1))
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.emit(2).run[F], PubSub.Closed)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
        _ <- assertResultF(hub.emit(2).run[F], PubSub.Closed)
        _ <- assertResultF(hub.emitChunk(Chunk(2)).run[F], PubSub.Closed)
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - awaitShutdown") {
      val t = for {
        hub <- newHub[Int](str)
        ctr <- F.ref[Int](0)
        f1 <- hub.subscribe.take(4).evalTap(_ => ctr.update(_ + 1)).compile.toVector.start
        f2 <- hub.subscribe.evalTap(_ => ctr.update(_ + 1)).compile.toVector.start
        f3 <- hub.subscribe.evalTap(_ => F.sleep(1.second) *> ctr.update(_ + 1)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.emit(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.emit(2).run[F], PubSub.Success)
        _ <- assertResultF(hub.emitChunk(Chunk(3, 4)).run[F], PubSub.Success)
        _ <- assertResultF(hub.emit(5).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
        shutDown <- F.deferred[Unit]
        awaitFib <- F.uncancelable { poll => poll(hub.awaitShutdown).flatMap { _ => shutDown.complete(()) } }.start
        _ <- this.tick
        _ <- assertResultF(shutDown.tryGet, None)
        _ <- shutDown.get
        _ <- awaitFib.joinWithNever
        _ <- assertResultF(ctr.get, 4 + (2 * 5))
        _ <- assertResultF(f1.joinWithNever, Vector(1, 2, 3, 4))
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3, 4, 5))
        _ <- assertResultF(f3.joinWithNever, Vector(1, 2, 3, 4, 5))
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - subscribe with non-default strategy") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe(dropOldest(1)).evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.emit(1).run[F], PubSub.Success)
        _ <- this.tick // make sure they receive the 1st, and then `f2` starts to sleep
        _ <- assertResultF(hub.emit(2).run[F], PubSub.Success)
        _ <- assertResultF(hub.emit(3).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- f1.joinWithNever
        // whatever `f1` does, `f2` must use `dropOldest(1)`:
        _ <- assertResultF(f2.joinWithNever, Vector(1, 3))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - numberOfSubscriptions") {
      val t = for {
        hub <- newHub[Int](str)
        _ <- assertResultF(hub.numberOfSubscriptions.run, 0)
        f1 <- hub.subscribe.compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        _ <- assertResultF(hub.numberOfSubscriptions.run, 1)
        f2 <- hub.subscribe.compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        _ <- assertResultF(hub.numberOfSubscriptions.run, 2)
        _ <- assertResultF(hub.emit(1).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.numberOfSubscriptions.run, 2)
        f3 <- hub.subscribe.take(2).compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        _ <- assertResultF(hub.numberOfSubscriptions.run, 3)
        _ <- assertResultF(hub.emit(2).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.numberOfSubscriptions.run, 3)
        _ <- f1.cancel
        _ <- this.tickAll
        _ <- assertResultF(hub.numberOfSubscriptions.run, 2)
        _ <- assertResultF(hub.emit(3).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.numberOfSubscriptions.run, 1)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- hub.awaitShutdown
        _ <- assertResultF(hub.numberOfSubscriptions.run, 0)
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(f3.joinWithNever, Vector(2, 3))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - initial Rxn should run for each subscription") {
      val t = for {
        hub <- newHub[Int](str)
        ctr <- Ref[Int](0).run[F]
        vec = hub.subscribeWithInitial(str, ctr.getAndUpdate(_ + 1)).compile.toVector
        fib1 <- vec.start
        _ <- this.tickAll
        fib2 <- vec.start
        _ <- this.tickAll
        fib3 <- vec.start
        _ <- this.tickAll
        _ <- assertResultF(hub.emit(42).run, PubSub.Success)
        _ <- hub.close.run
        _ <- hub.awaitShutdown
        _ <- assertResultF(fib1.joinWithNever, Vector(0, 42))
        _ <- assertResultF(fib2.joinWithNever, Vector(1, 42))
        _ <- assertResultF(fib3.joinWithNever, Vector(2, 42))
        _ <- assertResultF(ctr.get.run, 3)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - chunk bigger than capacity is an error") {
      val bsize = str.fold(-1, s => s, s => s, s => s)
      this.assume(bsize != -1)
      val ch = Chunk.from((1 to (bsize + 1)).toList)
      val t = for {
        hub <- newHub[Int](str)
        fib <- hub.subscribe.compile.toList.start
        _ <- this.tickAll
        r <- hub.emitChunk(ch).run[F].attempt
        _ <- assertF(clue(r).fold(_.isInstanceOf[IllegalArgumentException], _ => false))
        _ <- assertResultF(hub.emit(42).run, PubSub.Success)
        _ <- hub.close.run
        _ <- assertResultF(fib.joinWithNever, List(42))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - subscribe/close race (ticked)") {
      val t = for {
        hub <- newHub[Int](str)
        rr <- F.both(
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(1)).compile.toVector.start,
            hub.close.run,
          ),
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(2)).compile.toVector.start,
            hub.subscribeWithInitial(str, Rxn.pure(3)).compile.toVector.start,
          ),
        )
        ((fib1, closeResult), (fib2, fib3)) = rr
        _ <- if (closeResult eq PubSub.Backpressured) {
          hub.awaitShutdown
        } else {
          assertEqualsF(closeResult, PubSub.Success)
        }
        r1 <- fib1.joinWithNever
        r2 <- fib2.joinWithNever
        r3 <- fib3.joinWithNever
        _ <- assertF((clue(r1) == Vector()) || (r1 == Vector(1)))
        _ <- assertF((clue(r2) == Vector()) || (r2 == Vector(2)))
        _ <- assertF((clue(r3) == Vector()) || (r3 == Vector(3)))
      } yield ()
      t.replicateA_(if (isJs()) 1 else 50)
    }

    test(s"$name - subscribe/close/publish race (ticked)") {
      val t = for {
        hub <- newHub[Int](str)
        rr <- F.both(
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(1)).compile.toVector.start,
            hub.emit(9).run,
          ),
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(2)).compile.toVector.start,
            F.cede *> hub.close.run,
          ),
        )
        ((fib1, emitResult), (fib2, closeResult)) = rr
        _ <- if (closeResult eq PubSub.Backpressured) {
          hub.awaitShutdown
        } else {
          assertEqualsF(closeResult, PubSub.Success)
        }
        r1 <- fib1.joinWithNever
        r2 <- fib2.joinWithNever
        _ <- if (emitResult eq PubSub.Closed) {
          for {
            _ <- assertF((clue(r1) == Vector()) || (r1 == Vector(1)))
            _ <- assertF((clue(r2) == Vector()) || (r2 == Vector(2)))
          } yield ()
        } else {
          for {
            _ <- assertEqualsF(emitResult, PubSub.Success)
            _ <- assertF((clue(r1) == Vector()) || (r1 == Vector(1)) || (r1 == Vector(1, 9)))
            _ <- assertF((clue(r2) == Vector()) || (r2 == Vector(2)) || (r2 == Vector(2, 9)))
          } yield ()
        }
      } yield ()
      t.replicateA_(if (isJs()) 1 else 50)
    }
  }

  private def droppingTests(
    name: String,
    str: PubSub.OverflowStrategy,
    bufferSize: Int,
  ): Unit = {

    test(s"$name - closing mustn't conflict with item dropping") {
      val t = for {
        _ <- assertF(bufferSize > 2)
        hub <- newHub[Int](str)
        fib <- hub.subscribe.compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        rss <- (1 to bufferSize).toList.traverse(i => hub.emit(i).run[F]) // fill the queue
        _ <- assertF(rss.forall(_ == PubSub.Success))
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        vec <- fib.joinWithNever
        _ <- assertEqualsF(vec, (1 to bufferSize).toVector)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name - Initial element mustn't count against the buffer size") {
      val t = for {
        _ <- assertF(bufferSize > 2)
        hub <- newHub[Int](str)
        latch <- Promise[Unit].run
        fib <- hub.subscribeWithInitial(str, latch.complete(()).as(0)).compile.toVector.start
        _ <- latch.get[F] *> (1 to bufferSize).toList.traverse_(i => hub.emit(i).run[F])
        _ <- hub.close.run
        _ <- assertResultF(fib.joinWithNever, 0 +: (1 to bufferSize).toVector)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }
  }

  private def noBackpressureTests(name: String, str: PubSub.OverflowStrategy): Unit = {

    test(s"$name - should never backpressure") {
      for {
        hub <- newHub[Int](str)
        fib <- hub.subscribe.evalMap(_ => F.never[Int]).compile.toVector.start // infinitely slow subscriber
        _ <- this.tickAll // wait for subscription to happen
        pub = (hub.emit : (Int => Rxn[PubSub.Result]))
        _ <- (1 to (1024 * 256)).toList.traverse(i => assertResultF(pub(i).run[F], PubSub.Success))
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- fib.cancel
        _ <- assertResultF(fib.join, Outcome.canceled[F, Throwable, Vector[Int]])
      } yield ()
    }
  }

  private def singleElementBufferTests(
    name: String,
    str: PubSub.OverflowStrategy,
  ): Unit = {

    test(s"$name - single element buffer") {
      for {
        hub <- newHub[Int](str)
        fib1 <- hub.subscribe.compile.toVector.start
        fib2 <- hub.subscribe.take(2).compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        _ <- hub.emit(1).run[F]
        _ <- this.tickAll
        _ <- hub.emit(2).run[F]
        _ <- this.tickAll
        _ <- hub.emit(3).run[F]
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(fib1.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(fib2.joinWithNever, Vector(1, 2))
      } yield ()
    }
  }
}
