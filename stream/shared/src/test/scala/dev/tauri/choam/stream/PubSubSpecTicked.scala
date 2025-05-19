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
import cats.effect.IO
import fs2.Chunk

import PubSub.OverflowStrategy
import PubSub.OverflowStrategy.{ dropOldest, dropNewest, backpressure, unbounded }

final class PubSubSpecTicked_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with PubSubSpecTicked[IO]

trait PubSubSpecTicked[F[_]]
  extends BaseSpecAsyncF[F]
  with async.AsyncReactiveSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  commonTests("DropOldest", dropOldest(64))
  droppingTests("DropOldest", dropOldest(4), 4)
  noBackpressureTests("DropOldest", dropOldest(64))
  singleElementBufferTests("DropOldest", dropOldest(1))

  test("DropOldest - should drop oldest elements") {
      for {
        hub <- PubSub[F, Int](dropOldest(3)).run[F]
        fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
        slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run, PubSub.Success)
        _ <- this.tick // make sure they receive the 1st, and then start to sleep
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)).run, PubSub.Success)
        _ <- assertResultF(hub.publish(4).run, PubSub.Success) // buffers full
        _ <- assertResultF(hub.publish(5).run, PubSub.Success)
        _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
        _ <- assertResultF(hub.publish(6).run, PubSub.Success)
        _ <- assertResultF(hub.close.run, PubSub.Backpressured)
        _ <- assertResultF(fast.joinWithNever, Vector(1, 3, 4, 5, 6))
        _ <- assertResultF(slow.joinWithNever, Vector(1, 4, 5, 6))
      } yield ()
  }

  commonTests("DropNewest", dropNewest(64))
  droppingTests("DropNewest", dropNewest(4), 4)
  noBackpressureTests("DropNewest", dropNewest(64))
  singleElementBufferTests("DropNewest", dropNewest(1))

  test("DropNewest - should drop newest elements") {
      for {
        hub <- PubSub[F, Int](dropNewest(3)).run[F]
        fast <- hub.subscribe.evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
        slow <- hub.subscribe.evalTap(_ => F.sleep(1.second)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run, PubSub.Success)
        _ <- this.tick // make sure they receive the 1st, and then start to sleep
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)).run, PubSub.Success)
        _ <- assertResultF(hub.publish(4).run, PubSub.Success) // buffers full
        _ <- assertResultF(hub.publish(5).run, PubSub.Success) // this is dropped
        _ <- this.advanceAndTick(0.1.second) // `fast` can dequeue
        _ <- assertResultF(hub.publishChunk(Chunk(6, 7)).run, PubSub.Success) // this is dropped
        _ <- assertResultF(hub.close.run, PubSub.Backpressured)
        _ <- assertResultF(fast.joinWithNever, Vector(1, 2, 3, 4, 6, 7))
        _ <- assertResultF(slow.joinWithNever, Vector(1, 2, 3, 4))
      } yield ()
  }

  commonTests("Unbounded", unbounded)
  noBackpressureTests("Unbounded", unbounded)

  commonTests("Backpressure", backpressure(64))
  singleElementBufferTests("Backpressure", backpressure(1))

  private def commonTests(name: String, str: OverflowStrategy): Unit = {

    test(s"$name - basics") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)).run[F], PubSub.Success)
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.publish(4).run[F], PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(5, 6)).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(f1.joinWithNever, Vector(1, 2, 3, 4, 5, 6))
        _ <- assertResultF(f3.joinWithNever, Vector(2, 3, 4, 5, 6, 7))
      } yield ()
    }

    test(s"$name - closing") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        f3 <- hub.subscribe.map(_ + 1).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
        _ <- assertResultF(hub.publish(2).run[F], PubSub.Closed)
        _ <- assertResultF(hub.publishChunk(Chunk(2)).run[F], PubSub.Closed)
        _ <- assertResultF(f1.joinWithNever, Vector(1))
        _ <- assertResultF(f2.joinWithNever, Vector(1))
        _ <- assertResultF(f3.joinWithNever, Vector(2))
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
      } yield ()
    }

    test(s"$name - closing without subscribers") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.take(1).compile.toVector.start
        f2 <- hub.subscribe.take(3).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(2, 3)).run[F], PubSub.Success)
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Success)
        _ <- assertResultF(f1.joinWithNever, Vector(1))
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(hub.publish(2).run[F], PubSub.Closed)
        _ <- assertResultF(hub.subscribe.compile.toVector, Vector.empty)
        _ <- assertResultF(hub.publish(2).run[F], PubSub.Closed)
        _ <- assertResultF(hub.publishChunk(Chunk(2)).run[F], PubSub.Closed)
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
      } yield ()
    }

    test(s"$name - awaitShutdown") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        ctr <- F.ref[Int](0)
        f1 <- hub.subscribe.take(4).evalTap(_ => ctr.update(_ + 1)).compile.toVector.start
        f2 <- hub.subscribe.evalTap(_ => ctr.update(_ + 1)).compile.toVector.start
        f3 <- hub.subscribe.evalTap(_ => F.sleep(1.second) *> ctr.update(_ + 1)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run[F], PubSub.Success)
        _ <- assertResultF(hub.publish(2).run[F], PubSub.Success)
        _ <- assertResultF(hub.publishChunk(Chunk(3, 4)).run[F], PubSub.Success)
        _ <- assertResultF(hub.publish(5).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
        _ <- hub.awaitShutdown
        _ <- assertResultF(ctr.get, 4 + (2 * 5))
        _ <- assertResultF(f1.joinWithNever, Vector(1, 2, 3, 4))
        _ <- assertResultF(f2.joinWithNever, Vector(1, 2, 3, 4, 5))
        _ <- assertResultF(f3.joinWithNever, Vector(1, 2, 3, 4, 5))
        _ <- assertResultF(hub.close.run[F], PubSub.Closed)
      } yield ()
    }

    test(s"$name - subscribe with non-default strategy") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe(dropOldest(1)).evalTap(_ => F.sleep(0.1.second)).compile.toVector.start
        _ <- this.tickAll // wait for subscriptions to happen
        _ <- assertResultF(hub.publish(1).run[F], PubSub.Success)
        _ <- this.tick // make sure they receive the 1st, and then `f2` starts to sleep
        _ <- assertResultF(hub.publish(2).run[F], PubSub.Success)
        _ <- assertResultF(hub.publish(3).run[F], PubSub.Success)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- f1.joinWithNever
        // whatever `f1` does, `f2` must use `dropOldest`:
        _ <- assertResultF(f2.joinWithNever, Vector(1, 3))
      } yield ()
    }
  }

  private def droppingTests(
    name: String,
    str: PubSub.OverflowStrategy,
    bufferSize: Int,
  ): Unit = {

    test(s"$name - closing mustn't conflict with item dropping") {
      for {
        _ <- assertF(bufferSize > 2)
        hub <- PubSub[F, Int](str).run[F]
        fib <- hub.subscribe.compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        rss <- (1 to bufferSize).toList.traverse(i => hub.publish(i).run[F]) // fill the queue
        _ <- assertF(rss.forall(_ == PubSub.Success))
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        vec <- fib.joinWithNever
        _ <- assertEqualsF(vec, (1 to bufferSize).toVector)
      } yield ()
    }
  }

  private def noBackpressureTests(name: String, str: PubSub.OverflowStrategy): Unit = {

    test(s"$name - should never backpressure") {
      for {
        hub <- PubSub[F, Int](str).run[F]
        fib <- hub.subscribe.evalMap(_ => F.never[Int]).compile.toVector.start // infinitely slow subscriber
        _ <- this.tickAll // wait for subscription to happen
        pub = (hub.publish _ : (Int => Axn[PubSub.Result]))
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
        hub <- PubSub[F, Int](str).run[F]
        fib1 <- hub.subscribe.compile.toVector.start
        fib2 <- hub.subscribe.take(2).compile.toVector.start
        _ <- this.tickAll // wait for subscription to happen
        _ <- hub.publish(1).run[F]
        _ <- this.tickAll
        _ <- hub.publish(2).run[F]
        _ <- this.tickAll
        _ <- hub.publish(3).run[F]
        _ <- this.tickAll
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured)
        _ <- assertResultF(fib1.joinWithNever, Vector(1, 2, 3))
        _ <- assertResultF(fib2.joinWithNever, Vector(1, 2))
      } yield ()
    }
  }
}
