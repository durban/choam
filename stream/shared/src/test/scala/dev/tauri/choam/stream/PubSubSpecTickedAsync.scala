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
  }
}
