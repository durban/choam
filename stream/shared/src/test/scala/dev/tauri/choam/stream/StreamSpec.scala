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

import fs2.{ Stream, Chunk }

import core.AsyncReactiveSpec
import async.{ AsyncQueue, UnboundedQueue, BoundedQueue, Promise }
import syntax._

final class StreamSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with StreamSpec[IO]

trait StreamSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec =>

  test("UnboundedQueue to stream") {
    def check(q: UnboundedQueue[String]): F[Unit] = {
      for {
        _ <- assumeF(this.mcasImpl.isThreadSafe)
        fibVec <- q.stream.take(8).compile.toVector.start
        _ <- (1 to 8).toList.traverse { idx => q.enqueue[F](idx.toString) }
        _ <- assertResultF(fibVec.joinWithNever, (1 to 8).map(_.toString).toVector)
        _ <- List(9, 10).traverse { idx => q.enqueue[F](idx.toString) }
        _ <- assertResultF(q.deque, "9")
        _ <- assertResultF(q.deque, "10")
      } yield ()
    }
    for {
      q1 <- AsyncQueue.unbounded[String].run[F]
      q2 <- AsyncQueue.unboundedWithSize[String].run[F]
      q3 <- AsyncQueue.ringBuffer[String](capacity = 128).run[F]
      q4 <- AsyncQueue.dropping[String](capacity = 128).run[F]
      _ <- check(q1)
      _ <- check(q2)
      _ <- check(q3)
      _ <- check(q4)
    } yield ()
  }

  test("BoundedQueue to stream") {
    def check(q: BoundedQueue[Option[String]]): F[Unit] = {
      for {
        _ <- assumeF(this.mcasImpl.isThreadSafe)
        fibVec <- Stream.fromQueueNoneTerminated(q.toCats, limit = 4).compile.toVector.start
        _ <- (1 to 8).toList.traverse { idx => q.enqueue(Some(idx.toString)) }
        _ <- q.enqueue(None)
        _ <- assertResultF(fibVec.joinWithNever, (1 to 8).map(_.toString).toVector)
        fib2 <- List(9, 10).traverse { idx => q.enqueue(Some(idx.toString)) }.start
        _ <- assertResultF(q.deque, Some("9"))
        _ <- assertResultF(q.deque, Some("10"))
        _ <- fib2.joinWithNever
      } yield ()
    }
    for {
      q1 <- BoundedQueue.array[Option[String]](bound = 10).run[F]
      q2 <- BoundedQueue.linked[Option[String]](bound = 10).run[F]
      _ <- check(q1)
      _ <- check(q2)
    } yield ()
  }

  test("AsyncQueue extension methods") {
    for {
      // .stream:
      q <- AsyncQueue.unbounded[String].run[F]
      _ <- q.enqueue[F]("foo")
      qr <- q.stream.take(1).compile.toVector
      _ <- assertEqualsF(qr, Vector("foo"))
      // .streamNoneTerminated:
      qOpt <- AsyncQueue.unbounded[Option[String]].run[F]
      _ <- qOpt.enqueue[F](Some("foo")) >> qOpt.enqueue[F](None)
      qOptR <- qOpt.streamNoneTerminated.compile.toVector
      _ <- assertEqualsF(qOptR, Vector("foo"))
      // .streamFromChunks:
      qChunk <- AsyncQueue.unbounded[Chunk[String]].run[F]
      _ <- qChunk.enqueue[F](Chunk("foo", "bar"))
      qChunkR <- qChunk.streamFromChunks.take(2).compile.toVector
      _ <- assertEqualsF(qChunkR, Vector("foo", "bar"))
      // .streamFromChunksNoneTerminated:
      qOptChunk <- AsyncQueue.unbounded[Option[Chunk[String]]].run[F]
      _ <- qOptChunk.enqueue[F](Some(Chunk("foo", "bar"))) >> qOptChunk.enqueue[F](None)
      qOptChunkR <- qOptChunk.streamFromChunksNoneTerminated.compile.toVector
      _ <- assertEqualsF(qOptChunkR, Vector("foo", "bar"))
    } yield ()
  }

  test("Stream interrupter (Promise#toCats)") {
    val N = 20L
    val one = 0.1.second
    val many = one * N
    for {
      p <- Promise[Either[Throwable, Unit]].run[F]
      fib <- Stream
        .awakeEvery(one)
        .zip(Stream.iterate(0)(_ + 1))
        .map(_._2)
        .interruptWhen(p.toCats)
        .compile.toVector.start
      _ <- F.sleep(many)
      _ <- p.complete0.run[F](Right(()))
      vec <- fib.joinWithNever
      _ <- assertEqualsF(
        vec,
        (0 until vec.length).toVector
      )
      _ <- assertF(clue(vec.length) <= (N * 3))
    } yield ()
  }
}
