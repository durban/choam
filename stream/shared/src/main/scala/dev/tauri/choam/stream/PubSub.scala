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

import java.util.concurrent.atomic.AtomicLong

import scala.collection.immutable.LongMap

import cats.syntax.all._
import cats.effect.kernel.Async
import fs2.{ Stream, Chunk, Pull }

import async.{ AsyncReactive, AsyncQueue, AsyncQueueSource, BoundedQueue, OverflowQueue }
import data.QueueSink

sealed abstract class PubSub[F[_], A] { // TODO:0.5: finish this

  def subscribe: Stream[F, A]

  def publish(a: A): Axn[PubSub.Result]

  def publishChunk(ch: Chunk[A]): Axn[PubSub.Result]

  def close: Axn[PubSub.Result]

  def awaitShutdown: F[Unit]
}

object PubSub {

  sealed abstract class Result
  final object Closed extends Result
  final object Backpressured extends Result
  final object Success extends Result

  sealed abstract class OverflowStrategy

  final object OverflowStrategy {

    sealed abstract class Bounded extends OverflowStrategy {
      def bufferSize: Int // TODO:0.5: currently we're counting chunks, not items!
    }

    final class DropOldest private (override val bufferSize: Int) extends Bounded
    final object DropOldest {
      final def apply(bs: Int): DropOldest = new DropOldest(bs)
    }

    final class DropNewest private (override val bufferSize: Int) extends Bounded
    final object DropNewest {
      final def apply(bs: Int): DropNewest = new DropNewest(bs)
    }

    final class Backpressure private (override val bufferSize: Int) extends Bounded
    final object Backpressure {
      final def apply(bs: Int): Backpressure = new Backpressure(bs)
    }

    final object Unbounded extends OverflowStrategy
  }

  final def apply[F[_] : AsyncReactive, A](str: OverflowStrategy): Axn[PubSub[F, A]] = {
    val mkQueue: Axn[AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]]] = str match {
      case str: OverflowStrategy.Bounded =>
        require(str.bufferSize > 0)
        str match {
          case str: OverflowStrategy.DropOldest => OverflowQueue.ringBuffer(str.bufferSize)
          case str: OverflowStrategy.DropNewest => OverflowQueue.droppingQueue(str.bufferSize)
          case str: OverflowStrategy.Backpressure => BoundedQueue.array(str.bufferSize)
        }
      case OverflowStrategy.Unbounded => AsyncQueue.unbounded
    }
    Axn.unsafe.delay { new AtomicLong }.flatMapF { nextId =>
      Ref(LongMap.empty[Subscription[F, A]]).flatMapF { subscriptions =>
        Ref(false).map { isClosed =>
          new PubSubImpl[F, A](
            nextId,
            subscriptions,
            mkQueue,
            isClosed,
          )
        }
      }
    }
  }

  private[this] final class PubSubImpl[F[_], A](
    nextId: AtomicLong,
    subscriptions: Ref[LongMap[Subscription[F, A]]],
    mkQueue: Axn[AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]]], // NB: must have capacity > 0
    isClosed: Ref[Boolean],
  )(implicit F: AsyncReactive[F]) extends PubSub[F, A] {

    private[this] implicit def FF: Async[F] =
      F.asyncInst

    final override def subscribe: Stream[F, A] = {
      val acqSubs = FF.delay(nextId.getAndIncrement()).flatMap { id =>
        F.apply(mkQueue.flatMapF { queue =>
          val subs = new Subscription[F, A](id, queue, isClosed)
          subscriptions.update(_.updated(id, subs)).as(subs)
        })
      }
      Stream.bracket(acqSubs)(_.close(subscriptions).run[F]).flatMap(_.stream)
    }

    final override def publish(a: A): Axn[Result] = {
      publishChunk(Chunk.singleton(a))
    }

    final override def publishChunk(ch: Chunk[A]): Axn[Result] = {
      isClosed.get.flatMapF { isClosed =>
        if (isClosed) {
          Axn.pure(Closed)
        } else {
          subscriptions.get.flatMapF { subsMap =>
            val itr = subsMap.valuesIterator
            var acc = Axn.pure[Success.type](Success)
            while (itr.hasNext) {
              val pub1 = itr.next().publishChunkOrRetry(ch)
              acc = acc >>> pub1
            }
            acc + Axn.pure(Backpressured)
          }
        }
      }
    }

    final override def close: Axn[Result] = {
      isClosed.getAndUpdate(_ => true).flatMapF { wasClosed =>
        if (wasClosed) {
          Axn.pure(Closed)
        } else {
          subscriptions.get.flatMapF { subsMap =>
            val itr = subsMap.valuesIterator
            var acc = Axn.unit
            while (itr.hasNext) {
              val subs = itr.next()
              acc = acc *> subs.close(subscriptions)
            }
            acc.as(Success) // TODO
          }
        }
      }
    }

    final override def awaitShutdown: F[Unit] = {
      sys.error("TODO")
    }
  }

  private[this] final class Subscription[F[_], A](
    id: Long,
    queue: AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]],
    isClosed: Ref[Boolean],
  )(implicit F: AsyncReactive[F]) {

    private[this] implicit def FF: Async[F] =
      F.asyncInst

    final def publishChunkOrRetry(ch: Chunk[A]): Axn[Success.type] = {
      queue.tryEnqueue.provide(ch).flatMapF { ok =>
        if (ok) {
          Axn.pure(Success)
        } else {
          Rxn.unsafe.retry
        }
      }
    }

    final def stream: Stream[F, A] = {
      consume(Chunk.empty).stream
    }

    private[this] final def consume(acc: Chunk[A]): Pull[F, A, Unit] = {
      Pull.eval(F.apply(queue.tryDeque)).flatMap {
        case Some(null) =>
          // `close` is signalling us that we're done:
          Pull.output(acc) *> Pull.done
        case Some(chunk) =>
          // collect multiple chunk into a bigger one:
          consume(acc ++ chunk)
        case None =>
          Pull.output[F, A](acc) *> Pull.eval(F.apply(isClosed.get)).flatMap { isClosed =>
            if (isClosed) {
              // double check queue, because there is a
              // race between `output` above, and more
              // items being put into the queue:
              consume(Chunk.empty)
            } else {
              // suspend waiting for more in the queue:
              // TODO: There may be a race here: when preparing to
              // TODO: suspend, the queue can become *full*,
              // TODO: and then the `null` can't be enqueued
              // TODO: by `close`, and then we suspend...
              Pull.eval(queue.deque).flatMap { chunk =>
                if (chunk eq null) {
                  // `close` is signalling us that we're done:
                  Pull.done
                } else {
                  consume(chunk)
                }
              }
            }
          }
      }
    }

    final def close(subscriptions: Ref[LongMap[Subscription[F, A]]]): Axn[Unit] = {
      // Note: if `tryEnqueue` fails the queue is full, so `consume`
      // will check `isClosed`, so it will realize that we're closed.
      queue.tryEnqueue.provide(null : Chunk[A]) >>> subscriptions.update(_.removed(this.id))
    }
  }
}
