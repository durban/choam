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

sealed abstract class PubSub[F[_], R <: PubSub.Result, A] { // TODO:0.5: finish this

  def subscribe: Stream[F, A]

  def publish(a: A): Axn[R]

  def publishChunk(ch: Chunk[A]): Axn[R]

  def close: Axn[R]

  def awaitShutdown: F[Unit]
}

object PubSub {

  // TODO:0.5: currently we're counting chunks, not items!

  sealed abstract class Result
  sealed abstract class ClosedOrSuccess extends Result
  final object Closed extends ClosedOrSuccess
  final object Backpressured extends Result
  final object Success extends ClosedOrSuccess

  private[this] val _axnClosed = Axn.pure(Closed)
  private[this] val _axnBackpressured = Axn.pure(Backpressured)
  private[this] val _axnSuccess = Axn.pure(Success)

  sealed abstract class OverflowStrategy {

    type R >: ClosedOrSuccess <: Result

    private[PubSub] def newPubSubImpl[F[_] : AsyncReactive, A](
      nextId: AtomicLong,
      subscriptions: Ref[LongMap[Subscription[F, R, A]]],
      isClosed: Ref[Boolean],
    ): PubSub[F, R, A]
  }

  final object OverflowStrategy {

    final class Backpressure private (val bufferSize: Int) extends OverflowStrategy {

      final override type R = Result

      require(bufferSize > 0)

      private[PubSub] final override def newPubSubImpl[F[_] : AsyncReactive, A](
        nextId: AtomicLong,
        subscriptions: Ref[LongMap[Subscription[F, Result, A]]],
        isClosed: Ref[Boolean],
      ): PubSub[F, Result, A] = {
        new BackpressurePubSubImpl[F, A](nextId, subscriptions, BoundedQueue.array[Chunk[A]](bufferSize), isClosed)
      }
    }

    final object Backpressure {
      final def apply(bs: Int): Backpressure = new Backpressure(bs)
    }

    sealed abstract class NoBackpressure extends OverflowStrategy {

      final override type R = ClosedOrSuccess

      private[PubSub] final override def newPubSubImpl[F[_] : AsyncReactive, A](
        nextId: AtomicLong,
        subscriptions: Ref[LongMap[Subscription[F, ClosedOrSuccess, A]]],
        isClosed: Ref[Boolean],
      ): PubSub[F, ClosedOrSuccess, A] = {
        val mkQueue = this match {
          case str: OverflowStrategy.DropOldest => OverflowQueue.ringBuffer[Chunk[A]](str.bufferSize)
          case str: OverflowStrategy.DropNewest => OverflowQueue.droppingQueue[Chunk[A]](str.bufferSize)
          case Unbounded => AsyncQueue.unbounded[Chunk[A]]
        }
        new NoBackpressurePubSubImpl[F, A](nextId, subscriptions, mkQueue, isClosed)
      }
    }

    final object Unbounded extends NoBackpressure

    final class DropOldest private (val bufferSize: Int) extends NoBackpressure {
      require(bufferSize > 0)
    }

    final object DropOldest {
      final def apply(bs: Int): DropOldest = new DropOldest(bs)
    }

    final class DropNewest private (val bufferSize: Int) extends NoBackpressure {
      require(bufferSize > 0)
    }

    final object DropNewest {
      final def apply(bs: Int): DropNewest = new DropNewest(bs)
    }
  }

  final def apply[F[_] : AsyncReactive, A](str: OverflowStrategy): Axn[PubSub[F, str.R, A]] = {
    Axn.unsafe.delay { new AtomicLong }.flatMapF { nextId =>
      Ref(LongMap.empty[Subscription[F, str.R, A]]).flatMapF { subscriptions =>
        Ref(false).map { isClosed =>
          str.newPubSubImpl(nextId, subscriptions, isClosed)
        }
      }
    }
  }

  private[this] final class NoBackpressurePubSubImpl[F[_], A](
    nextId: AtomicLong,
    subscriptions: Ref[LongMap[Subscription[F, ClosedOrSuccess, A]]],
    mkQueue: Axn[AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]]], // NB: must have capacity > 0
    isClosed: Ref[Boolean],
  )(implicit F: AsyncReactive[F]) extends PubSubImpl[F, ClosedOrSuccess, A](nextId, subscriptions, mkQueue, isClosed) {

    protected[this] final override def fallbackBackpressured(acc: Axn[Success.type]): Axn[ClosedOrSuccess] =
      acc

    final override def retryIfNeedsBackpressure(enqResult: Boolean): Axn[Success.type] =
      _axnSuccess // we never backpressure, regardless of the enqResult
  }

  private[this] final class BackpressurePubSubImpl[F[_], A](
    nextId: AtomicLong,
    subscriptions: Ref[LongMap[Subscription[F, Result, A]]],
    mkQueue: Axn[AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]]], // NB: must have capacity > 0
    isClosed: Ref[Boolean],
  )(implicit F: AsyncReactive[F]) extends PubSubImpl[F, Result, A](nextId, subscriptions, mkQueue, isClosed) {

    protected[this] final override def fallbackBackpressured(acc: Axn[Success.type]): Axn[Result] =
      acc + _axnBackpressured

    final override def retryIfNeedsBackpressure(enqResult: Boolean): Axn[Success.type] =
      if (enqResult) _axnSuccess else Rxn.unsafe.retry
  }

  private[this] sealed abstract class PubSubImpl[F[_], R >: ClosedOrSuccess <: Result, A](
    nextId: AtomicLong,
    val subscriptions: Ref[LongMap[Subscription[F, R, A]]],
    mkQueue: Axn[AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]]], // NB: must have capacity > 0
    val isClosed: Ref[Boolean],
  )(implicit F: AsyncReactive[F]) extends PubSub[F, R, A] {

    protected[this] def fallbackBackpressured(acc: Axn[Success.type]): Axn[R]

    def retryIfNeedsBackpressure(enqResult: Boolean): Axn[Success.type]

    private[this] implicit def FF: Async[F] =
      F.asyncInst

    final override def subscribe: Stream[F, A] = {
      val acqSubs = FF.delay(nextId.getAndIncrement()).flatMap { id =>
        F.apply(mkQueue.flatMapF { queue =>
          val subs = new Subscription[F, R, A](id, queue, this)
          subscriptions.update(_.updated(id, subs)).as(subs)
        })
      }
      Stream.bracket(acqSubs)(_.close.run[F]).flatMap(_.stream)
    }

    final override def publish(a: A): Axn[R] = {
      publishChunk(Chunk.singleton(a))
    }

    final override def publishChunk(ch: Chunk[A]): Axn[R] = {
      isClosed.get.flatMapF { isClosed =>
        if (isClosed) {
          _axnClosed
        } else {
          subscriptions.get.flatMapF { subsMap =>
            val itr = subsMap.valuesIterator
            var acc = _axnSuccess
            while (itr.hasNext) {
              val pub1 = itr.next().publishChunkOrRetry(ch)
              acc = acc >>> pub1
            }
            fallbackBackpressured(acc)
          }
        }
      }
    }

    final override def close: Axn[R] = {
      isClosed.getAndUpdate(_ => true).flatMapF { wasClosed =>
        if (wasClosed) {
          _axnClosed
        } else {
          subscriptions.get.flatMapF { subsMap =>
            val itr = subsMap.valuesIterator
            var acc = Axn.unit
            while (itr.hasNext) {
              val subs = itr.next()
              acc = acc *> subs.close
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

  private[this] final class Subscription[F[_], R >: ClosedOrSuccess <: Result, A](
    id: Long,
    queue: AsyncQueueSource[Chunk[A]] with QueueSink[Chunk[A]],
    hub: PubSubImpl[F, R, A],
  )(implicit F: AsyncReactive[F]) {

    private[this] implicit def FF: Async[F] =
      F.asyncInst

    final def publishChunkOrRetry(ch: Chunk[A]): Axn[Success.type] = {
      queue.tryEnqueue.provide(ch).flatMapF { ok =>
        hub.retryIfNeedsBackpressure(ok)
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
          Pull.output[F, A](acc) *> Pull.eval(F.apply(hub.isClosed.get)).flatMap { isClosed =>
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

    final def close: Axn[Unit] = {
      // Note: if `tryEnqueue` fails the queue is full, so `consume`
      // will check `isClosed`, so it will realize that we're closed.
      queue.tryEnqueue.provide(null : Chunk[A]) >>> hub.subscriptions.update(_.removed(this.id))
    }
  }
}
