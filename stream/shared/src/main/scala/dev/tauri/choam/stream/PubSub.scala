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
import cats.effect.syntax.all._
import fs2.{ Stream, Chunk, Pull }

import core.{ Rxn, Ref, AsyncReactive }
import async.{ Promise, WaitList }
import data.UnboundedDeque

sealed abstract class PubSub[A]
  extends PubSub.Emit[A] // TODO: -> Publish[A]
  with PubSub.Subscribe[A] {



  // TODO: asFs2 : Topic[F, A] (or asTopic?)
}

object PubSub {

  sealed trait Emit[-A] {
    def emit(a: A): Rxn[PubSub.Result]
    def emitChunk(ch: Chunk[A]): Rxn[PubSub.Result]
    def close: Rxn[PubSub.Result]
  }

  sealed trait Publish[-A] extends Emit[A] {
    def publish[F[_]: AsyncReactive](a: A): F[PubSub.Result] // TODO: ClosedOrSuccess
    def publishChunk[F[_]: AsyncReactive](ch: Chunk[A]): F[PubSub.Result] // TODO: ClosedOrSuccess
  }

  sealed trait Subscribe[+A] {
    def subscribe[F[_]: AsyncReactive]: Stream[F, A]
    def awaitShutdown[F[_]: AsyncReactive]: F[Unit] // TODO: does this belong here?
    private[choam] def subscribe[F[_]: AsyncReactive](strategy: PubSub.OverflowStrategy): Stream[F, A]
    private[choam] def subscribeWithInitial[F[_]: AsyncReactive, B >: A](
      strategy: PubSub.OverflowStrategy,
      initial: Rxn[B],
    ): Stream[F, B]
    private[stream] def numberOfSubscriptions: Rxn[Int]
  }

  private[choam] final def emit[A]( // TODO: better name + make it public
    overflowStr: OverflowStrategy,
  ): Rxn[PubSub.Emit[A] & PubSub.Subscribe[A]] = {
    emit(overflowStr, Ref.AllocationStrategy.Default)
  }

  private[choam] final def emit[A]( // TODO: better name + make it public
    overflowStr: OverflowStrategy,
    allocStr: Ref.AllocationStrategy,
  ): Rxn[PubSub.Emit[A] & PubSub.Subscribe[A]] = {
    apply(overflowStr, allocStr)
  }

  final def apply[A](
    overflowStr: OverflowStrategy,
  ): Rxn[PubSub[A]] = {
    apply(overflowStr, Ref.AllocationStrategy.Default)
  }

  final def apply[A](
    overflowStr: OverflowStrategy,
    allocStr: Ref.AllocationStrategy,
  ): Rxn[PubSub[A]] = {
    // TODO: if `str` is padded, this AtomicLong should also be padded
    Rxn.unsafe.delay { new AtomicLong }.flatMap { nextId =>
      Ref(LongMap.empty[Subscription[A, _]], allocStr).flatMap { subscriptions =>
        Ref(false, allocStr).flatMap { isClosed =>
          Promise[Unit](allocStr).map { awaitClosed =>
            new PubSubImpl[A](nextId, subscriptions, isClosed, awaitClosed, overflowStr)
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

  private[this] final class SignalChunk[A](a: A) extends Chunk[A] {

    final override def size: Int =
      1

    final override def apply(i: Int): A = {
      if (i == 0) a
      else throw new IndexOutOfBoundsException
    }

    final override def copyToArray[O2 >: A](xs: Array[O2], start: Int): Unit = {
      xs(start) = a
    }

    protected final override def splitAtChunk_(n: Int): (Chunk[A], Chunk[A]) = {
      impossible("SignalChunk#splitAtChunk_")
    }
  }

  private[this] def signalChunk[A](a: A): Chunk[A] = {
    new SignalChunk(a)
  }

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
    private[PubSub] def newBuffer[F[_] : AsyncReactive, A](
      sizeRef: Ref[Int],
      underlying: UnboundedDeque[Chunk[A]],
      wl: WaitList[Chunk[A]],
    ): PubSubBuffer[A]

    private[PubSub] final def mkWaitList[A](
      underlying: UnboundedDeque[Chunk[A]],
      size: Ref[Int]
    ): Rxn[WaitList[Chunk[A]]] = {
      WaitList.apply[Chunk[A]]( // TODO: pass AllocationStrategy
        underlying.tryTakeLast.flatMap {
          case None =>
            Rxn.none
          case some @ Some(null) =>
            Rxn.pure(some)
          case some @ Some(_: SignalChunk[_]) =>
            Rxn.pure(some)
          case some @ Some(chunk) =>
            size.update { oldSize =>
              val newSize = oldSize - chunk.size
              _assert(newSize >= 0) // PubSubBuffer#enqueue guarantees this
              newSize
            }.as(some)
        },
        {
          case null =>
            underlying.addFirst(null)
          case chunk: SignalChunk[_] =>
            underlying.addFirst(chunk)
          case chunk =>
            size.update { oldSize =>
              val newSize = oldSize + chunk.size
              Predef.assert(newSize >= oldSize) // check overflow (unlikely)
              newSize
            } *> underlying.addFirst(chunk)
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

      private[PubSub] def newBuffer[F[_] : AsyncReactive, A](
        sizeRef: Ref[Int],
        underlying: UnboundedDeque[Chunk[A]],
        wl: WaitList[Chunk[A]],
      ): PubSubBuffer[A] = {
        new PubSubBuffer[A](bufferSize, sizeRef.get, wl) {
          protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
            Rxn.unsafe.retryWhenChanged
        }
      }
    }

    private final object Unbounded extends OverflowStrategy {

      private[PubSub] def newBuffer[F[_] : AsyncReactive, A](
        sizeRef: Ref[Int],
        underlying: UnboundedDeque[Chunk[A]],
        wl: WaitList[Chunk[A]],
      ): PubSubBuffer[A] = {
        new PubSubBuffer[A](Integer.MAX_VALUE, sizeRef.get, wl) {
          protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
            impossible(s"OverflowStrategy.Unbounded#handleOverflow (chunkSize = ${newChunk.size}, missingCapacity = $missingCapacity)")
        }
      }
    }

    private final class DropOldest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] def newBuffer[F[_] : AsyncReactive, A](
        sizeRef: Ref[Int],
        underlying: UnboundedDeque[Chunk[A]],
        wl: WaitList[Chunk[A]],
      ): PubSubBuffer[A] = {
        new PubSubBuffer[A](bufferSize, sizeRef.get, wl) {

          protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
            dropOldestN(missingCapacity) *> sizeRef.update(_ - missingCapacity) *> wl.set0(newChunk).as(Success)

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

    private final class DropNewest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[PubSub] def newBuffer[F[_] : AsyncReactive, A](
        sizeRef: Ref[Int],
        underlying: UnboundedDeque[Chunk[A]],
        wl: WaitList[Chunk[A]],
      ): PubSubBuffer[A] = {
        new PubSubBuffer[A](bufferSize, sizeRef.get, wl) {
          protected[this] final override def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result] =
            _axnSuccess
        }
      }
    }
  }

  private[this] sealed trait HandleSubscriptions {
    def removeSubscription(id: Long): Rxn[Unit]
    def isClosed: Ref[Boolean]
  }

  private[this] final class PubSubImpl[A](
    nextId: AtomicLong,
    val subscriptions: Ref[LongMap[Subscription[A, _]]],
    val isClosed: Ref[Boolean],
    awaitClosed: Promise[Unit],
    defaultStrategy: OverflowStrategy,
  ) extends PubSub[A] with HandleSubscriptions {

    final override def subscribe[F[_]](implicit F: AsyncReactive[F]): Stream[F, A] =
      this.subscribe(this.defaultStrategy)

    final override def subscribe[F[_]](strategy: PubSub.OverflowStrategy)(implicit F: AsyncReactive[F]): Stream[F, A] =
      this.subscribeWithInitial(strategy, Rxn.nullOf[A]).drop(1)

    final override def removeSubscription(id: Long): Rxn[Unit] =
      this.subscriptions.update(_.removed(id))

    private[choam] final override def subscribeWithInitial[F[_], B >: A](
      strategy: PubSub.OverflowStrategy,
      initial: Rxn[B],
    )(implicit F: AsyncReactive[F]): Stream[F, B] = {
      implicit val FF: Async[F] = F.asyncInst
      val acqSubs = FF.delay(nextId.getAndIncrement()).flatMap { id =>
        Promise[Unit].run[F].flatMap { sp =>
          val act: Rxn[Subscription[A, B]] = isClosed.get.flatMap { isClosed =>
            if (isClosed) {
              Rxn.pure(null : Subscription[A, B])
            } else {
              UnboundedDeque[Chunk[B]].flatMap { underlying =>
                Ref[Int](0).flatMap { size =>
                  strategy.mkWaitList(underlying, size).flatMap { wl =>
                    val buf = strategy.newBuffer[F, B](size, underlying, wl)
                    initial.flatMap { elem => buf.enqueue(signalChunk(elem)) } *> {
                      val subs = new Subscription[B, B](id, buf, sp, this)
                      subscriptions.update(_.updated(id, subs)).as(subs)
                    }
                  }
                }
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

    final override def emit(a: A): Rxn[Result] = {
      emitChunk(Chunk.singleton(a))
    }

    final override def emitChunk(ch: Chunk[A]): Rxn[Result] = {
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

    final override def awaitShutdown[F[_]](implicit F: AsyncReactive[F]): F[Unit] = {
      implicit val FF: Async[F] = F.asyncInst
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
    private[stream] final override def numberOfSubscriptions: Rxn[Int] = {
      subscriptions.get.map(_.size)
    }
  }

  private[PubSub] final class Subscription[-A, B >: A](
    id: Long,
    queue: PubSubBuffer[A],
    val awaitShutdown: Promise[Unit],
    hub: HandleSubscriptions,
  ) {

    final def publishChunkOrRetry(ch: Chunk[A]): Rxn[Result] = {
      queue.enqueue(ch)
    }

    final def stream[F[_]: AsyncReactive]: Stream[F, B] = {
      consume(Chunk.empty).stream
    }

    private[this] final def consume[F[_]](acc: Chunk[A])(implicit F: AsyncReactive[F]): Pull[F, B, Unit] = {
      implicit val FF: Async[F] = F.asyncInst
      Pull.eval(F.apply(queue.tryDequeue)).flatMap {
        case Some(null) =>
          // `close` is signalling us that we're done:
          Pull.output(acc) *> Pull.done
        case Some(chunk) =>
          // collect multiple chunks into a bigger one:
          consume(acc ++ chunk) // TODO: add a sizeLimit parameter
        case None =>
          Pull.output[F, B](acc) *> Pull.eval(F.apply(hub.isClosed.get)).flatMap { isClosed =>
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
      queue.enqueue(null).void
    }

    final def closeInternal[F[_]](implicit F: AsyncReactive[F]): F[Unit] = {
      implicit val FF: Async[F] = F.asyncInst
      F.apply(hub.removeSubscription(this.id)).guarantee(
        awaitShutdown.complete(()).void.run[F]
      )
    }
  }

  private[this] sealed abstract class PubSubBuffer[A](
    capacity: Int,
    getSize: Rxn[Int],
    wl: WaitList[Chunk[A]],
  ) {

    protected[this] def handleOverflow(newChunk: Chunk[A], missingCapacity: Int): Rxn[Result]

    final def enqueue(chunk: Chunk[A]): Rxn[Result] = getSize.flatMap { sz =>
      chunk match {
        case null =>
          wl.set0(null).as(Success)
        case _: SignalChunk[_] =>
          wl.set0(chunk).as(Success)
        case _ =>
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
    }

    final def tryDequeue: Rxn[Option[Chunk[A]]] = {
      wl.tryGet
    }

    final def dequeue[F[_]: AsyncReactive]: F[Chunk[A]] = {
      wl.asyncGet
    }
  }
}
