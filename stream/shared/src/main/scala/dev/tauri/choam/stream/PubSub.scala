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
import cats.effect.kernel.{ Async, Deferred }
import cats.effect.syntax.all._
import fs2.{ Stream, Chunk, Pull }

import core.{ Rxn, Axn }
import async.{ AsyncReactive, Promise, WaitList }
import data.UnboundedDeque

sealed abstract class PubSub[F[_], A] {

  def subscribe: Stream[F, A]

  def subscribe(strategy: PubSub.OverflowStrategy): Stream[F, A]

  def publish(a: A): Axn[PubSub.Result]

  def publishChunk(ch: Chunk[A]): Axn[PubSub.Result]

  def close: Axn[PubSub.Result]

  def awaitShutdown: F[Unit]
}

object PubSub {

  final def apply[F[_] : AsyncReactive, A](defaultStrategy: OverflowStrategy): Axn[PubSub[F, A]] = {
    Axn.unsafe.delay { new AtomicLong }.flatMapF { nextId =>
      Ref(LongMap.empty[Subscription[F, A]]).flatMapF { subscriptions =>
        Ref(false).flatMapF { isClosed =>
          Promise[Unit].map { awaitClosed =>
            new PubSubImpl[F, A](nextId, subscriptions, isClosed, awaitClosed, defaultStrategy)
          }
        }
      }
    }
  }

  sealed abstract class Result
  sealed abstract class ClosedOrSuccess extends Result
  final object Closed extends ClosedOrSuccess
  final object Backpressured extends Result
  final object Success extends ClosedOrSuccess

  private[this] val _axnClosed = Axn.pure(Closed)
  private[this] val _axnBackpressured = Axn.pure(Backpressured)
  private[this] val _axnSuccess = Axn.pure(Success)

  sealed abstract class OverflowStrategy {

    // TODO: type R >: ClosedOrSuccess <: Result

    private[stream] final def fold[A](
      unbounded: A,
      backpressure: Int => A,
      dropOldest: Int => A,
      dropNewest: Int => A,
    ): A = this match {
      case OverflowStrategy.Unbounded => unbounded
      case bp: OverflowStrategy.Backpressure => backpressure(bp.bufferSize)
      case dro: OverflowStrategy.DropOldest => dropOldest(dro.bufferSize)
      case drn: OverflowStrategy.DropNewest => dropNewest(drn.bufferSize)
    }

    private[PubSub] def newBuffer[F[_] : AsyncReactive, A]: Axn[PubSubBuffer[F, A]]

    protected[this] final def mkWaitList[A](underlying: UnboundedDeque[Chunk[A]], size: Ref[Int]): Axn[WaitList[Chunk[A]]] = {
      WaitList.apply[Chunk[A]](
        tryGet = underlying.tryTakeLast.flatMapF {
          case None =>
            Axn.none
          case some @ Some(chunk) =>
            if (chunk eq null) Axn.pure(some)
            else size.update(_ - chunk.size).as(some)
        },
        syncSet = Rxn.computed(underlying.addFirst),
      )
    }
  }

  final object OverflowStrategy {

    final def unbounded: OverflowStrategy =
      Unbounded

    final def backpressure(bufferSize: Int): OverflowStrategy =
      new Backpressure(bufferSize)

    final def dropOldest(bufferSize: Int): OverflowStrategy =
      new DropOldest(bufferSize)

    final def dropNewest(bufferSize: Int): OverflowStrategy =
      new DropNewest(bufferSize)

    private final class Backpressure private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Axn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMapF { underlying =>
          Ref[Int](0).flatMapF { size =>
            mkWaitList(underlying, size).map { gwl =>
              new PubSubBuffer[F, A](bufferSize, size, gwl) {
                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Axn[Result] =
                  Rxn.unsafe.retryWhenChanged
              }
            }
          }
        }
      }
    }

    private final object Unbounded extends OverflowStrategy {

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Axn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMapF { underlying =>
          Ref[Int](0).flatMapF { size =>
            mkWaitList(underlying, size).map { gwl =>
              new PubSubBuffer[F, A](Integer.MAX_VALUE, size, gwl) {
                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Axn[Result] =
                  impossible(s"OverflowStrategy.Unbounded#handleOverflow (chunkSize = ${newChunk.size}, missingCapacity = $missingCapacity)")
              }
            }
          }
        }
      }
    }

    private final class DropOldest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Axn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMapF { underlying =>
          Ref[Int](0).flatMapF { size =>
            mkWaitList(underlying, size).map { gwl =>
              new PubSubBuffer[F, A](bufferSize, size, gwl) {

                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Axn[Result] =
                  dropOldestN(missingCapacity) >>> gwl.set0.provide(newChunk).map { inserted =>
                    _assert(inserted)
                    Success
                  }

                private[this] final def dropOldestN(n: Int): Axn[Unit] = {
                  underlying.tryTakeLast.flatMapF {
                    case None =>
                      Axn.unit
                    case Some(chunk) =>
                      val chunkSize = chunk.size
                      if (chunkSize < n) {
                        dropOldestN(n - chunkSize)
                      } else if (chunkSize > n) {
                        val putItBack = chunk.drop(n)
                        underlying.addLast(putItBack)
                      } else { // chunkSize == n
                        Axn.unit
                      }
                  } // NB: we don't update `size`, since we'll add the `newChunk` anyway
                }
              }
            }
          }
        }
      }
    }

    private final class DropNewest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Axn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMapF { underlying =>
          Ref[Int](0).flatMapF { size =>
            mkWaitList(underlying, size).map { gwl =>
              new PubSubBuffer[F, A](bufferSize, size, gwl) {
                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Axn[Result] =
                  _axnSuccess
              }
            }
          }
        }
      }
    }
  }

  private[this] final class PubSubImpl[F[_], A](
    nextId: AtomicLong,
    val subscriptions: Ref[LongMap[Subscription[F, A]]],
    val isClosed: Ref[Boolean],
    awaitClosed: Promise[Unit],
    defaultStrategy: OverflowStrategy,
  )(implicit F: AsyncReactive[F]) extends PubSub[F, A] {

    private[this] implicit def FF: Async[F] =
      F.asyncInst

    final override def subscribe: Stream[F, A] =
      this.subscribe(this.defaultStrategy)

    final override def subscribe(strategy: PubSub.OverflowStrategy): Stream[F, A] = {
      val acqSubs = FF.delay(nextId.getAndIncrement()).flatMap { id =>
        FF.deferred[Unit].flatMap { sd =>
          val act: Axn[Subscription[F, A]] = isClosed.get.flatMapF { isClosed =>
            if (isClosed) {
              Axn.pure(null : Subscription[F, A])
            } else {
              strategy.newBuffer[F, A].flatMapF { buf =>
                val subs = new Subscription[F, A](id, buf, sd, this)
                subscriptions.update(_.updated(id, subs)).as(subs)
              }
            }
          }
          F.apply(act)
        }
      }
      Stream.bracket(acqSubs) { subs =>
        if (subs eq null) FF.unit
        else subs.closeInternal
      }.flatMap { subs =>
        if (subs eq null) Stream.empty
        else subs.stream
      }
    }

    final override def publish(a: A): Axn[Result] = {
      publishChunk(Chunk.singleton(a))
    }

    final override def publishChunk(ch: Chunk[A]): Axn[Result] = {
      isClosed.get.flatMapF { isClosed =>
        if (isClosed) {
          _axnClosed
        } else {
          subscriptions.get.flatMapF { subsMap =>
            val itr = subsMap.valuesIterator
            var acc: Axn[Result] = _axnSuccess
            while (itr.hasNext) {
              val pub1 = itr.next().publishChunkOrRetry(ch)
              acc = acc >>> pub1
            }
            // TODO: If no subscription ever retries (because
            // TODO: all have a strategy which doesn't need it),
            // TODO: then we could avoid creating an `orElse`.
            Rxn.unsafe.orElse(acc, _axnBackpressured)
          }
        }
      }
    }

    final override def close: Axn[Result] = {
      isClosed.getAndUpdate(_ => true).flatMapF { wasClosed =>
        if (wasClosed) {
          _axnClosed
        } else {
          awaitClosed.complete1(()) *> subscriptions.get.flatMapF { subsMap =>
            val itr = subsMap.valuesIterator
            var acc = Axn.unit
            var cnt = 0L
            while (itr.hasNext) {
              cnt += 1L
              val subs = itr.next()
              acc = acc >>> subs.closeExternal
            }
            acc.as(if (cnt > 0L) Backpressured else Success)
          }
        }
      }
    }

    final override def awaitShutdown: F[Unit] = {
      awaitClosed.get[F] *> {
        def go: F[Unit] = {
          F.apply(subscriptions.get).flatMap { subscriptions =>
            val itr = subscriptions.valuesIterator
            if (itr.hasNext) {
              itr.next().awaitShutdown.get *> go // there could be others
            } else {
              FF.unit // we're done
            }
          }
        }

        go
      }
    }
  }

  private[PubSub] final class Subscription[F[_], A](
    id: Long,
    queue: PubSubBuffer[F, A],
    val awaitShutdown: Deferred[F, Unit],
    hub: PubSubImpl[F, A],
  )(implicit F: AsyncReactive[F]) {

    private[this] implicit def FF: Async[F] =
      F.asyncInst

    final def publishChunkOrRetry(ch: Chunk[A]): Axn[Result] = {
      queue.enqueue(ch)
    }

    final def stream: Stream[F, A] = {
      consume(Chunk.empty).stream
    }

    private[this] final def consume(acc: Chunk[A]): Pull[F, A, Unit] = {
      Pull.eval(F.apply(queue.tryDequeue)).flatMap {
        case Some(null) =>
          // `close` is signalling us that we're done:
          Pull.output(acc) *> Pull.done
        case Some(chunk) =>
          // collect multiple chunks into a bigger one:
          consume(acc ++ chunk) // TODO: add a sizeLimit parameter
        case None =>
          Pull.output[F, A](acc) *> Pull.eval(F.apply(hub.isClosed.get)).flatMap { isClosed =>
            if (isClosed) {
              // double check queue, because there is a
              // race between `output` above, and more
              // items being put into the queue:
              consume(Chunk.empty)
            } else {
              // suspend waiting for more in the queue:
              Pull.eval(queue.dequeue).flatMap { chunk =>
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

    final def closeExternal: Axn[Unit] = {
      queue.close
    }

    final def closeInternal: F[Unit] = {
      F.apply(hub.subscriptions.update(_.removed(this.id))).guarantee(
        awaitShutdown.complete(()).void
      )
    }
  }

  private[this] sealed abstract class PubSubBuffer[F[_], A](
    capacity: Int,
    size: Ref[Int],
    wl: WaitList[Chunk[A]],
  )(implicit F: AsyncReactive[F]) {

    protected[this] def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Axn[Result]

    final def close: Axn[Unit] = {
      wl.set0.provide(null).void
    }

    final def enqueue(chunk: Chunk[A]): Axn[Result] = size.get.flatMapF { sz =>
      val chunkSize = chunk.size
      if (chunkSize > capacity) {
        // impossible to publish a chunk this big
        _axnBackpressured
      } else {
        val left = capacity - sz
        if (left >= chunkSize) { // OK
          wl.set0.provide(chunk).flatMapF { inserted =>
            if (inserted) size.set1(sz + chunkSize).as(Success)
            else _axnSuccess
          }
        } else {
          val missing = chunkSize - left
          this.handleOverflow(chunk, missing)
        }
      }
    }

    final def tryDequeue: Axn[Option[Chunk[A]]] = {
      wl.tryGet
    }

    final def dequeue: F[Chunk[A]] = {
      wl.asyncGet
    }
  }
}
