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

import cats.effect.IO
import fs2.Chunk
import fs2.concurrent.Topic

import core.Ref

import PubSub.OverflowStrategy
import PubSub.OverflowStrategy.{ dropOldest, dropNewest, backpressure, unbounded }

final class PubSubSpecTickedAsync_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with PubSubSpecTickedAsync[IO]

trait PubSubSpecTickedAsync[F[_]] extends PubSubSpecTicked[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected[this] final override type H[A] = PubSub[A]

  protected[this] final override def newHub[A](str: PubSub.OverflowStrategy): F[PubSub[A]] =
    PubSub.async[A](str).run[F]

  test("Backpressure (async) - backpressuring") {
    val t = for {
      hub <- newHub[Int](backpressure(3))
      fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
      slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
      _ <- this.tickAll // wait for subscriptions to happen
      _ <- assertResultF(hub.publish(1), PubSub.Success)
      _ <- this.tick // make sure they receive the 1st, and then start to sleep
      _ <- assertResultF(hub.publishChunk(Chunk(2, 3)), PubSub.Success)
      _ <- assertResultF(hub.publish(4), PubSub.Success) // buffers full
      d <- F.deferred[Unit]
      // this will suspend:
      fib <- F.uncancelable { poll =>
        poll(hub.publish(5)).flatMap { _ => d.complete(()) }
      }.start
      _ <- this.tick
      _ <- assertResultF(d.tryGet, None) // still suspended
      _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
      _ <- assertResultF(d.tryGet, None) // but still suspended
      _ <- this.tickAll // consume all items
      _ <- fib.joinWithNever
      _ <- assertResultF(hub.publishChunk(Chunk(10, 11, 12)), PubSub.Success)
      _ <- assertResultF(hub.close.run, PubSub.Backpressured)
      _ <- assertResultF(fast.joinWithNever, Vector(1, 2, 3, 4, 5, 10, 11, 12))
      _ <- assertResultF(slow.joinWithNever, Vector(1, 2, 3, 4, 5, 10, 11, 12))
    } yield ()
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  test("Backpressure (async) - awaitShutdown while being backpressured") {
    val t = for {
      hub <- newHub[Int](backpressure(3))
      fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
      slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
      _ <- this.tickAll // wait for subscriptions to happen
      _ <- assertResultF(hub.publish(1), PubSub.Success)
      _ <- this.tick // make sure they receive the 1st, and then start to sleep
      _ <- assertResultF(hub.publishChunk(Chunk(2, 3)), PubSub.Success)
      _ <- assertResultF(hub.publish(4), PubSub.Success) // buffers full
      shutDown <- F.deferred[Unit]
      awaitFib <- F.uncancelable { poll => poll(hub.awaitShutdown).flatMap(_ => shutDown.complete(())) }.start
      d <- F.deferred[PubSub.Result]
      // this will suspend:
      fib <- F.uncancelable { poll =>
        poll(hub.publish(5)).flatMap(d.complete)
      }.start
      _ <- this.tick
      _ <- assertResultF(shutDown.tryGet, None)
      _ <- assertResultF(d.tryGet, None) // still suspended
      _ <- assertResultF(hub.close.run, PubSub.Backpressured)
      _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
      _ <- assertResultF(shutDown.tryGet, None)
      tgr <- d.tryGet
      _ <- assertF(tgr.isEmpty || (tgr == Some(PubSub.Closed)))
      _ <- this.tickAll // consume all items
      _ <- fib.joinWithNever
      _ <- awaitFib.joinWithNever
      _ <- assertResultF(shutDown.tryGet, Some(()))
      fastRes <- fast.joinWithNever
      slowRes <- slow.joinWithNever
      _ <- assertEqualsF(fastRes, slowRes)
      pubRes <- d.get
      _ <- assertEqualsF(pubRes, PubSub.Closed)
      _ <- assertEqualsF(fastRes, Vector(1, 2, 3, 4))
    } yield ()
    t.replicateA_(if (isJvm()) 50 else 5)
  }

  commonAsyncTests("DropOldest", dropOldest(64))
  commonAsyncTests("DropNewest", dropNewest(64))
  commonAsyncTests("Unbounded", unbounded)
  commonAsyncTests("Backpressure", backpressure(64))

  private def commonAsyncTests(name: String, str: OverflowStrategy): Unit = {

    test(s"$name (async) - basics") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1), PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)), PubSub.Success)
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.publish(4), PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(5, 6)), PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(f1.joinWithNever, Vector(1, 2, 3, 4, 5, 6))
        _ <- assertResultF(f3.joinWithNever, Vector(2, 3, 4, 5, 6, 7))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name (async) - closing") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1), PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
        _ <- assertResultF(hub.publish(2), PubSub.Closed)
        _ <- assertResultF(hub.publishChunk(Chunk(2)), PubSub.Closed)
        _ <- assertResultF(f1.joinWithNever, Vector(1))
        _ <- assertResultF(f2.joinWithNever, Vector(1))
        _ <- assertResultF(f3.joinWithNever, Vector(2))
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name (async) - closing without subscribers") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.take(1).compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1), PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)), PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Success)
        _ <- assertResultF(f1.joinWithNever, Vector(1))
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.publish(2), PubSub.Closed)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
        _ <- assertResultF(hub.publish(2), PubSub.Closed)
        _ <- assertResultF(hub.publishChunk(Chunk(2)), PubSub.Closed)
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name (async) - awaitShutdown") {
      val t = for {
        hub <- newHub[Int](str)
        ctr <- F.ref[Int](0)
        f1 <- hub.subscribe.take(4).evalTap(_ => ctr.update(_ + 1)).compile.toVector.start
        f2 <- hub.subscribe.evalTap(_ => ctr.update(_ + 1)).compile.toVector.start
        f3 <- hub.subscribe.evalTap(_ => F.sleep(1.second) *> ctr.update(_ + 1)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1), PubSub.Success)
        _ <- assertResultF(hub.publish(2), PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(3, 4)), PubSub.Success)
        _ <- assertResultF(hub.publish(5), PubSub.Success)
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

    test(s"$name (async) - subscribe with non-default strategy") {
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe(dropOldest(1)).evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1), PubSub.Success)
        _ <- this.tick // make sure they receive the 1st, and then `f2` starts to sleep
        _ <- assertResultF(hub.publish(2), PubSub.Success)
        _ <- assertResultF(hub.emit(3).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- f1.joinWithNever
        // whatever `f1` does, `f2` must use `dropOldest(1)`:
        _ <- assertResultF(f2.joinWithNever, Vector(1, 3))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name (async) - initial Rxn should run for each subscription") {
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
        _ <- assertResultF(hub.publish(42), PubSub.Success)
        _ <- hub.close.run
        _ <- hub.awaitShutdown
        _ <- assertResultF(fib1.joinWithNever, Vector(0, 42))
        _ <- assertResultF(fib2.joinWithNever, Vector(1, 42))
        _ <- assertResultF(fib3.joinWithNever, Vector(2, 42))
        _ <- assertResultF(ctr.get.run, 3)
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }

    test(s"$name (async) - chunk bigger than capacity is an error") {
      val bsize = str.fold(-1, s => s, s => s, s => s)
      this.assume(bsize != -1)
      val ch = Chunk.from((1 to (bsize + 1)).toList)
      val t = for {
        hub <- newHub[Int](str)
        fib <- hub.subscribe.compile.toList.start
        _ <- this.tickAll
        r <- hub.publishChunk(ch).attempt
        _ <- assertF(clue(r).fold(_.isInstanceOf[IllegalArgumentException], _ => false))
        _ <- assertResultF(hub.publish(42), PubSub.Success)
        _ <- hub.close.run
        _ <- assertResultF(fib.joinWithNever, List(42))
      } yield ()
      t.replicateA_(if (isJvm()) 50 else 5)
    }
  }

  test("FS2 Topic") {
    for {
      hub <- newHub[Int](PubSub.OverflowStrategy.backpressure(64))
      topic = hub.asFs2
      _ <- assertResultF(topic.isClosed, false)
      waitingFib <- topic.closed.start
      _ <- this.tickAll
      _ <- assertResultF(topic.subscribers.take(1).compile.toList, List(0))
      sizeFib <- topic.subscribers.compile.toList.start
      _ <- this.tickAll
      fib1 <- topic.subscribe(64).compile.toList.start
      _ <- this.tickAll
      fc <- topic.subscribeAwait(64).allocated.flatMap { case (s, close) =>
        s.compile.toList.start.map { fib => (fib, close) }
      }
      (fib2, close2) = fc
      fib3 <- hub.subscribe.compile.toList.start
      _ <- this.tickAll
      _ <- assertResultF(topic.publish1(1), Right(()))
      _ <- assertResultF(topic.publish1(2), Right(()))
      _ <- assertResultF(hub.emit(3).run, PubSub.Success)
      _ <- assertResultF(hub.publish(4), PubSub.Success)
      _ <- assertResultF(topic.publish1(5), Right(()))
      _ <- assertResultF(topic.close, Right(()))
      _ <- assertResultF(topic.isClosed, true)
      _ <- waitingFib.joinWithNever
      _ <- assertResultF(topic.close, Left(Topic.Closed))
      _ <- assertResultF(hub.close.run, PubSub.Closed)
      _ <- assertResultF(fib1.joinWithNever, List(1, 2, 3, 4, 5))
      _ <- assertResultF(fib2.joinWithNever, List(1, 2, 3, 4, 5))
      _ <- close2
      _ <- assertResultF(fib3.joinWithNever, List(1, 2, 3, 4, 5))
      _ <- assertResultF(sizeFib.joinWithNever, List(0, 1, 2, 3))
    } yield ()
  }

  test("FS2 Topic backpressure") {
    val t = for {
      hub <- newHub[Int](PubSub.OverflowStrategy.backpressure(64))
      topic = hub.asFs2
      cb <- cats.effect.std.CyclicBarrier(2)
      fib1 <- topic.subscribe(64).compile.toList.start
      _ <- this.tickAll
      fib2 <- topic.subscribe(2).evalTap { _ => cb.await }.compile.toList.start
      _ <- this.tickAll
      d <- F.deferred[Unit]
      pubFib <- F.uncancelable { poll =>
        poll(
          topic.publish1(1) *>
          F.sleep(1.second) *> // wait for streams to suspend
          topic.publish1(2) *>
          topic.publish1(3) *>
          topic.publish1(4)
        ).flatTap { _ =>
          d.complete(()).void
        }
      }.start
      _ <- assertResultF(d.tryGet, None)
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- cb.await.replicateA_(4)
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, Some(()))
      _ <- pubFib.joinWithNever
      _ <- assertResultF(topic.close, Right(()))
      _ <- assertResultF(fib1.joinWithNever, List(1, 2, 3, 4))
      _ <- assertResultF(fib2.joinWithNever, List(1, 2, 3, 4))
    } yield ()
    t.replicateA_(if (isJs()) 5 else 50)
  }
}
