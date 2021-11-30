/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import async.{ AsyncQueue, BoundedQueue, Promise, AsyncReactiveSpec }
import syntax._

final class StreamSpec_Prim_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with StreamSpecPrim[IO]

final class StreamSpec_Derived_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with StreamSpecDerived[IO]

sealed trait StreamSpecDerived[F[_]] extends StreamSpec[F] { this: KCASImplSpec =>
  final override def newAsyncQueue[A]: F[AsyncQueue[F, A]] =
    AsyncQueue.derived[F, A].run[F]
}

sealed trait StreamSpecPrim[F[_]] extends StreamSpec[F] { this: KCASImplSpec =>
  final override def newAsyncQueue[A]: F[AsyncQueue[F, A]] =
    AsyncQueue.primitive[F, A].run[F]
}

sealed trait StreamSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: KCASImplSpec =>

  def newAsyncQueue[A]: F[AsyncQueue[F, A]]

  test("AsyncQueue to stream") {
    for {
      q <- newAsyncQueue[String]
      fibVec <- q.stream.take(8).compile.toVector.start
      _ <- (1 to 8).toList.traverse { idx => q.enqueue[F](idx.toString) }
      _ <- assertResultF(fibVec.joinWithNever, (1 to 8).map(_.toString).toVector)
      _ <- List(9, 10).traverse { idx => q.enqueue[F](idx.toString) }
      _ <- assertResultF(q.deque, "9")
      _ <- assertResultF(q.deque, "10")
    } yield ()
  }

  test("BoundedQueue to stream") {
    for {
      q <- BoundedQueue[F, Option[String]](maxSize = 10).run[F]
      fibVec <- Stream.fromQueueNoneTerminated(q.toCats, limit = 4).compile.toVector.start
      _ <- (1 to 8).toList.traverse { idx => q.enqueue(Some(idx.toString)) }
      _ <- q.enqueue(None)
      _ <- assertResultF(fibVec.joinWithNever, (1 to 8).map(_.toString).toVector)
      _ <- List(9, 10).traverse { idx => q.enqueue(Some(idx.toString)) }
      _ <- assertResultF(q.deque, Some("9"))
      _ <- assertResultF(q.deque, Some("10"))
    } yield ()
  }

  test("AsyncQueue extension methods") {
    for {
      // .stream:
      q <- newAsyncQueue[String]
      _ <- q.enqueue[F]("foo")
      qr <- q.stream.take(1).compile.toVector
      _ <- assertEqualsF(qr, Vector("foo"))
      // .streamNoneTerminated:
      qOpt <- newAsyncQueue[Option[String]]
      _ <- qOpt.enqueue[F](Some("foo")) >> qOpt.enqueue[F](None)
      qOptR <- qOpt.streamNoneTerminated.compile.toVector
      _ <- assertEqualsF(qOptR, Vector("foo"))
      // .streamFromChunks:
      qChunk <- newAsyncQueue[Chunk[String]]
      _ <- qChunk.enqueue[F](Chunk("foo", "bar"))
      qChunkR <- qChunk.streamFromChunks.take(2).compile.toVector
      _ <- assertEqualsF(qChunkR, Vector("foo", "bar"))
      // .streamFromChunksNoneTerminated:
      qOptChunk <- newAsyncQueue[Option[Chunk[String]]]
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
      p <- Promise[F, Either[Throwable, Unit]].run[F]
      fib <- Stream
        .awakeEvery(one)
        .zip(Stream.iterate(0)(_ + 1))
        .map(_._2)
        .interruptWhen(p.toCats)
        .compile.toVector.start
      _ <- F.sleep(many)
      _ <- p.complete[F](Right(()))
      vec <- fib.joinWithNever
      _ <- assertEqualsF(
        vec,
        (0 until vec.length).toVector
      )
      _ <- assertF(clue(vec.length) <= (N * 3))
    } yield ()
  }

  test("RxnSignallingRef") {
    val N = 1000
    def writer(ref: RxnSignallingRef[F, Int], next: Int): F[Unit] = {
      if (next > N) {
        F.unit
      } else {
        (ref.set(next) *> F.cede) >> writer(ref, next + 1)
      }
    }
    def checkList(l: List[Int], max: Int): F[Unit] = {
      def go(l: List[Int], prev: Int): F[Unit] = l match {
        case Nil =>
          F.unit
        case h :: t =>
          // list should be increasing:
          (assertF(clue(prev) < clue(h)) *> assertF(h <= max)) >> (
            go(t, prev = h)
          )
      }
      // we assume that at least a fifth of the updates are not lost:
      assertF(clue(l.length) >= (max / 5)) *> go(l, -1)
    }
    def checkListeners(ref: RxnSignallingRef[F, Int], min: Int, max: Int): F[Unit] = {
      F.defer {
        val listeners = ref.asInstanceOf[Fs2SignallingRefWrapper[F, Int]].listeners.values.run[F]
        listeners.flatMap(n => assertF(clue(n.size) <= clue(max)) *> assertF(clue(n.size) >= clue(min)))
      }
    }
    for {
      ref <- signallingRef[F, Int](initial = 0).run[F]
      listener = ref
        .discrete
        .evalTap(_ => F.cede)
        .takeThrough(_ < N)
        .compile
        .toList
      f1 <- listener.start
      f2 <- listener.start
      f3 <- (ref.discrete.take(10).evalTap { _ =>
        // we check the number of listeners during and after the stream
        checkListeners(ref, min = 1, max = 3)
      } ++ Stream.exec(checkListeners(ref, min = 0, max = 2))).compile.toList.start
      fw <- writer(ref, 1).start
      _ <- fw.joinWithNever
      _ <- f3.joinWithNever // raises error if not cleaned up properly
      l1 <- f1.joinWithNever
      _ <- checkList(l1, max = N)
      l2 <- f2.joinWithNever
      _ <- checkList(l2, max = N)
      _ <- checkListeners(ref, min = 0, max = 0)
    } yield ()
  }
}
