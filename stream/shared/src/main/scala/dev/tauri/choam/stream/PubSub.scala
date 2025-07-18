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

import core.{ Rxn, Ref, AsyncReactive }
import async.{ Promise, WaitList }
import data.UnboundedDeque

sealed abstract class PubSub[F[_], A] {

  def subscribe: Stream[F, A]

  def subscribe(strategy: PubSub.OverflowStrategy): Stream[F, A]

  def publish(a: A): Rxn[PubSub.Result]

  def publishChunk(ch: Chunk[A]): Rxn[PubSub.Result]

  def close: Rxn[PubSub.Result]

  def awaitShutdown: F[Unit]

  /** Only for testing! */
  private[stream] def numberOfSubscriptions: F[Int]
}

object PubSub {

  final def apply[F[_] : AsyncReactive, A](
    defaultStrategy: OverflowStrategy,
  ): Rxn[PubSub[F, A]] = {
    apply(defaultStrategy, Ref.AllocationStrategy.Default)
  }

  final def apply[F[_] : AsyncReactive, A](
    defaultStrategy: OverflowStrategy,
    str: Ref.AllocationStrategy,
  ): Rxn[PubSub[F, A]] = {
    // TODO: if `str` is padded, this AtomicLong should also be padded
    Rxn.unsafe.delay { new AtomicLong }.flatMap { nextId =>
      Ref(LongMap.empty[Subscription[F, A]], str).flatMap { subscriptions =>
        Ref(false, str).flatMap { isClosed =>
          Promise[Unit](str).map { awaitClosed =>
            new PubSubImpl[F, A](nextId, subscriptions, isClosed, awaitClosed, defaultStrategy)
          }
        }
      }
    }
  }

  sealed abstract class Result
  // TODO: sealed abstract class ClosedOrSuccess extends Result
  final object Closed extends Result // TODO: extends ClosedOrSuccess
  final object Backpressured extends Result
  final object Success extends Result // TODO: extends ClosedOrSuccess

  private[this] val _axnClosed = Rxn.pure(Closed)
  private[this] val _axnBackpressured = Rxn.pure(Backpressured)
  private[this] val _axnSuccess = Rxn.pure(Success)

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

    // TODO: add an `str: AllocationStrategy` parameter, and use it
    private[PubSub] def newBuffer[F[_] : AsyncReactive, A]: Rxn[PubSubBuffer[F, A]]

    protected[this] final def mkWaitList[A](
      underlying: UnboundedDeque[Chunk[A]],
      size: Ref[Int]
    ): Rxn[WaitList[Chunk[A]]] = {
      WaitList.apply[Chunk[A]]( // TODO: pass AllocationStrategy
        underlying.tryTakeLast.flatMap {
          case None =>
            Rxn.none
          case some @ Some(chunk) =>
            if (chunk eq null) Rxn.pure(some)
            else size.update(_ - chunk.size).as(some)
        },
        { chunk =>
          if (chunk ne null) {
            size.update { oldSize =>
              val newSize = oldSize + chunk.size
              Predef.assert(newSize >= oldSize) // check overflow (unlikely)
              newSize
            } *> underlying.addFirst(chunk)
          } else {
            underlying.addFirst(chunk)
          }
        },
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

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Rxn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMap { underlying =>
          Ref[Int](0).flatMap { size =>
            mkWaitList(underlying, size).map { wl =>
              new PubSubBuffer[F, A](bufferSize, size.get, wl) {
                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
                  Rxn.unsafe.retryWhenChanged
              }
            }
          }
        }
      }
    }

    private final object Unbounded extends OverflowStrategy {

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Rxn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMap { underlying =>
          Ref[Int](0).flatMap { size =>
            mkWaitList(underlying, size).map { wl =>
              new PubSubBuffer[F, A](Integer.MAX_VALUE, size.get, wl) {
                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
                  impossible(s"OverflowStrategy.Unbounded#handleOverflow (chunkSize = ${newChunk.size}, missingCapacity = $missingCapacity)")
              }
            }
          }
        }
      }
    }

    private final class DropOldest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Rxn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMap { underlying =>
          Ref[Int](0).flatMap { size =>
            mkWaitList(underlying, size).map { wl =>
              new PubSubBuffer[F, A](bufferSize, size.get, wl) {

                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
                  dropOldestN(missingCapacity) *> size.update(_ - missingCapacity) *> wl.set0(newChunk).as(Success)

                private[this] final def dropOldestN(n: Int): Rxn[Unit] = {
                  underlying.tryTakeLast.flatMap {
                    case None =>
                      Rxn.unit
                    case Some(chunk) =>
                      val chunkSize = chunk.size
                      if (chunkSize < n) {
                        dropOldestN(n - chunkSize)
                      } else if (chunkSize > n) {
                        val putItBack = chunk.drop(n)
                        underlying.addLast(putItBack)
                      } else { // chunkSize == n
                        Rxn.unit
                      }
                  }
                }
              }
            }
          }
        }
      }
    }

    private final class DropNewest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] final override def newBuffer[F[_] : AsyncReactive, A]: Rxn[PubSubBuffer[F, A]] = {
        UnboundedDeque[Chunk[A]].flatMap { underlying =>
          Ref[Int](0).flatMap { size =>
            mkWaitList(underlying, size).map { wl =>
              new PubSubBuffer[F, A](bufferSize, size.get, wl) {
                protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
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
          val act: Rxn[Subscription[F, A]] = isClosed.get.flatMap { isClosed =>
            if (isClosed) {
              Rxn.pure(null : Subscription[F, A])
            } else {
              strategy.newBuffer[F, A].flatMap { buf =>
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

    final override def publish(a: A): Rxn[Result] = {
      publishChunk(Chunk.singleton(a))
    }

    final override def publishChunk(ch: Chunk[A]): Rxn[Result] = {
      isClosed.get.flatMap { isClosed =>
        if (isClosed) {
          _axnClosed
        } else {
          if (ch.isEmpty) {
            _axnSuccess
          } else {
            subscriptions.get.flatMap { subsMap =>
              val itr = subsMap.valuesIterator
              var acc: Rxn[Result] = _axnSuccess
              while (itr.hasNext) {
                val pub1 = itr.next().publishChunkOrRetry(ch)
                acc = acc *> pub1
              }
              // TODO: If no subscription ever retries (because
              // TODO: all have a strategy which doesn't need it),
              // TODO: then we could avoid creating an `orElse`.
              Rxn.unsafe.orElse(acc, _axnBackpressured)
            }
          }
        }
      }
    }

    final override def close: Rxn[Result] = {
      isClosed.getAndUpdate(_ => true).flatMap { wasClosed =>
        if (wasClosed) {
          _axnClosed
        } else {
          awaitClosed.complete(()) *> subscriptions.get.flatMap { subsMap =>
            val itr = subsMap.valuesIterator
            var acc = Rxn.unit
            var cnt = 0L
            while (itr.hasNext) {
              cnt += 1L
              val subs = itr.next()
              acc = acc *> subs.closeExternal
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

    /** Only for testing! */
    private[stream] final override def numberOfSubscriptions: F[Int] = {
      subscriptions.get.map(_.size).run[F]
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

    final def publishChunkOrRetry(ch: Chunk[A]): Rxn[Result] = {
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

    final def closeExternal: Rxn[Unit] = {
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
    getSize: Rxn[Int],
    wl: WaitList[Chunk[A]],
  )(implicit F: AsyncReactive[F]) {

    protected[this] def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result]

    final def close: Rxn[Unit] = {
      wl.set0(null).void
    }

    final def enqueue(chunk: Chunk[A]): Rxn[Result] = getSize.flatMap { sz =>
      val chunkSize = chunk.size
      _assert(chunkSize > 0)
      if (chunkSize > capacity) {
        // impossible to publish a chunk this big
        _axnBackpressured
      } else {
        val left = capacity - sz
        if (left >= chunkSize) { // OK
          wl.set0(chunk).as(Success)
        } else {
          val missing = chunkSize - left
          this.handleOverflow(chunk, missing)
        }
      }
    }

    final def tryDequeue: Rxn[Option[Chunk[A]]] = {
      wl.tryGet
    }

    final def dequeue: F[Chunk[A]] = {
      wl.asyncGet
    }
  }
}
