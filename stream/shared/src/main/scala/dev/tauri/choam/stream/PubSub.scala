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

import cats.~>
import cats.syntax.all._
import cats.effect.kernel.{ Async, Cont, MonadCancel }
import cats.effect.syntax.all._
import fs2.{ Stream, Chunk, Pull }

import core.{ Rxn, Ref, AsyncReactive }
import async.{ Promise, WaitList, GenWaitList }
import data.UnboundedDeque

sealed abstract class PubSub[A]
  extends PubSub.Simple[A]
  with PubSub.Publish[A] {

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

  sealed abstract class Simple[A]
    extends PubSub.Emit[A]
    with PubSub.Subscribe[A]

  final def simple[A](
    overflowStr: OverflowStrategy,
  ): Rxn[PubSub.Simple[A]] = {
    simple(overflowStr, Ref.AllocationStrategy.Default)
  }

  final def simple[A](
    overflowStr: OverflowStrategy,
    allocStr: Ref.AllocationStrategy,
  ): Rxn[PubSub.Simple[A]] = {
    impl(overflowStr, allocStr, publishCanSuspend = false)
  }

  final def async[A](
    overflowStr: OverflowStrategy,
  ): Rxn[PubSub[A]] = {
    async(overflowStr, Ref.AllocationStrategy.Default)
  }

  final def async[A](
    overflowStr: OverflowStrategy,
    allocStr: Ref.AllocationStrategy,
  ): Rxn[PubSub[A]] = {
    impl(overflowStr, allocStr, publishCanSuspend = true)
  }

  private[this] final def impl[A](
    overflowStr: OverflowStrategy,
    allocStr: Ref.AllocationStrategy,
    publishCanSuspend: Boolean,
  ): Rxn[PubSub[A]] = {
    // TODO: if `str` is padded, this AtomicLong should also be padded
    Rxn.unsafe.delay { new AtomicLong }.flatMap { nextId =>
      Ref(LongMap.empty[Subscription[A, _]], allocStr).flatMap { subscriptions =>
        Ref(false, allocStr).flatMap { isClosed =>
          Promise[Unit](allocStr).map { awaitClosed =>
            new PubSubImpl[A](nextId, subscriptions, isClosed, awaitClosed, overflowStr, publishCanSuspend)
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

  private[this] def signalChunk[A](a: A): SignalChunk[A] = {
    new SignalChunk(a)
  }

  private[this] sealed abstract class HandleOverflowResult
  private[this] final object Done extends HandleOverflowResult
  private[this] final object Retry extends HandleOverflowResult
  private[this] final object Suspend extends HandleOverflowResult

  sealed abstract class OverflowStrategy {

    // TODO: type R >: ClosedOrSuccess <: Result

    private[stream] def capacityOrMax: Int

    private[PubSub] def handleOverflow[A](
      underlying: UnboundedDeque[Chunk[A]],
      sizeRef: Ref[Int],
      newChunk: Chunk[A],
      missingCapacity: Int,
      canSuspend: Boolean,
    ): Rxn[HandleOverflowResult]

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
    private[PubSub] final def newBuffer[F[_], A](canSuspend: Boolean): Rxn[PubSubBuffer[A]] = {
      UnboundedDeque[Chunk[A]].flatMap { underlying =>
        Ref[Int](0).flatMap { sizeRef =>
          mkWaitList(underlying, this.capacityOrMax, sizeRef, canSuspend = canSuspend).map { wl =>
            new PubSubBuffer[A](sizeRef, underlying, wl)
          }
        }
      }
    }

    private[this] final def mkWaitList[A](
      underlying: UnboundedDeque[Chunk[A]],
      capacity: Int,
      size: Ref[Int],
      canSuspend: Boolean,
    ): Rxn[GenWaitList[Chunk[A]]] = {
      if (!canSuspend) {
        WaitList.apply[Chunk[A]]( // TODO: pass AllocationStrategy
          tryGetUnderlying = this.tryGetUnderlying(underlying, size),
          setUnderlying = this.setUnderlying(_, underlying, capacity, size, canSuspend = false).map { ok =>
            _assert(ok)
            ()
          },
        )
      } else {
        GenWaitList.apply[Chunk[A]]( // TODO: pass AllocationStrategy
          tryGetUnderlying = this.tryGetUnderlying(underlying, size),
          trySetUnderlying = this.setUnderlying(_, underlying, capacity, size, canSuspend = true),
        )
      }
    }

    private[this] final def tryGetUnderlying[A](
      underlying: UnboundedDeque[Chunk[A]],
      size: Ref[Int],
    ): Rxn[Option[Chunk[A]]] = {
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
            _assert(newSize >= 0)
            newSize
          }.as(some)
      }
    }

    private[this] final def setUnderlying[A](
      ch: Chunk[A],
      underlying: UnboundedDeque[Chunk[A]],
      capacity: Int,
      size: Ref[Int],
      canSuspend: Boolean,
    ): Rxn[Boolean] = {
      ch match {
        case null =>
          underlying.addFirst(null).as(true)
        case chunk: SignalChunk[_] =>
          underlying.addFirst(chunk).as(true)
        case chunk =>
          val chunkSize = chunk.size
          _assert(chunkSize > 0)
          if (chunkSize > capacity) {
            // impossible to publish a chunk this big
            Rxn.unsafe.panic(new IllegalArgumentException(
              s"can't publish chunk of size ${chunkSize} to buffer with capacity ${capacity}"
            ))
          } else {

            def go: Rxn[Boolean] = {
              size.get.flatMap { oldSize =>
                val left = capacity - oldSize
                if (left >= chunkSize) { // OK
                  val newSize = oldSize + chunkSize
                  size.set(newSize) *> underlying.addFirst(chunk).as(true)
                } else {
                  val missing = chunkSize - left
                  this.handleOverflow(underlying, size, chunk, missing, canSuspend = canSuspend).flatMap {
                    case Done =>
                      Rxn.true_
                    case Retry =>
                      go // retry (`handleOverflow` changed `size`)
                    case Suspend =>
                      _assert(canSuspend)
                      Rxn.false_
                  }
                }
              }
            }

            go
          }
      }
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

      private[stream] final override def capacityOrMax: Int = bufferSize

      private[PubSub] final override def handleOverflow[A](
        underlying: UnboundedDeque[Chunk[A]],
        sizeRef: Ref[Int],
        newChunk: Chunk[A],
        missingCapacity: Int,
        canSuspend: Boolean,
      ): Rxn[HandleOverflowResult] = if (canSuspend) Rxn.pure(Suspend) else Rxn.unsafe.retryWhenChanged
    }

    private final object Unbounded extends OverflowStrategy {

      private[stream] final override def capacityOrMax: Int = Integer.MAX_VALUE

      private[PubSub] final override def handleOverflow[A](
        underlying: UnboundedDeque[Chunk[A]],
        sizeRef: Ref[Int],
        newChunk: Chunk[A],
        missingCapacity: Int,
        canSuspend: Boolean,
      ): Rxn[HandleOverflowResult] = impossible(
        s"OverflowStrategy.Unbounded#handleOverflow (..., chunkSize = ${newChunk.size}, missingCapacity = $missingCapacity)"
      )
    }

    private final class DropOldest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[stream] final override def capacityOrMax: Int = bufferSize

      private[PubSub] final override def handleOverflow[A](
        underlying: UnboundedDeque[Chunk[A]],
        sizeRef: Ref[Int],
        newChunk: Chunk[A],
        missingCapacity: Int,
        canSuspend: Boolean,
      ): Rxn[HandleOverflowResult] = {
        dropOldestN(underlying, missingCapacity, Nil) *> sizeRef.update { oldSize =>
          val newSize = oldSize - missingCapacity
          _assert(newSize >= 0)
          newSize
        }.as(Retry)
      }

      private[this] final def dropOldestN[A](
        underlying: UnboundedDeque[Chunk[A]],
        n: Int,
        putItBack: List[SignalChunk[_ <: A]],
      ): Rxn[Unit] = {
        underlying.tryTakeLast.flatMap {
          case None =>
            impossible(s"empty UnboundedDeque, but need to drop oldest ${n}")
          case Some(chunk: SignalChunk[_]) =>
            dropOldestN(underlying, n, chunk :: putItBack)
          case Some(chunk) =>
            val chunkSize = chunk.size
            if (chunkSize < n) {
              dropOldestN(underlying, n - chunkSize, putItBack)
            } else if (chunkSize > n) {
              val putItBack2 = chunk.drop(n)
              underlying.addLast(putItBack2) *> putItBack.traverse_(underlying.addLast)
            } else { // chunkSize == n
              putItBack.traverse_(underlying.addLast)
            }
        }
      }
    }

    private final class DropNewest private[OverflowStrategy] (val bufferSize: Int) extends OverflowStrategy {

      require(bufferSize > 0)

      private[stream] final override def capacityOrMax: Int = bufferSize

      private[PubSub] final override def handleOverflow[A](
        underlying: UnboundedDeque[Chunk[A]],
        sizeRef: Ref[Int],
        newChunk: Chunk[A],
        missingCapacity: Int,
        canSuspend: Boolean,
      ): Rxn[HandleOverflowResult] = {
        Rxn.pure(Done)
      }
    }
  }

  private[this] sealed trait HandleSubscriptions {
    def removeSubscription(id: Long): Rxn[Unit]
    def isClosed: Ref[Boolean]
  }

  private[this] final class SuspWith[A](val subs: Subscription[A, _], val cleanup: Rxn[Unit])

  private[this] final class PubSubImpl[A](
    nextId: AtomicLong,
    val subscriptions: Ref[LongMap[Subscription[A, _]]],
    val isClosed: Ref[Boolean],
    awaitClosed: Promise[Unit],
    defaultStrategy: OverflowStrategy,
    publishCanSuspend: Boolean,
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
              strategy.newBuffer[F, B](canSuspend = publishCanSuspend).flatMap { buf =>
                initial.flatMap { elem => buf.enqueueSignal(signalChunk(elem)) } *> {
                  val subs = new Subscription[B, B](id, buf, sp, this)
                  subscriptions.update(_.updated(id, subs)).as(subs)
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

    final override def publish[F[_]: AsyncReactive](a: A): F[PubSub.Result] = {
      publishChunk(Chunk.singleton(a))
    }

    final override def publishChunk[F[_]](ch: Chunk[A])(implicit F: AsyncReactive[F]): F[PubSub.Result] = {
      _assert(publishCanSuspend)
      F.asyncInst.uncancelable { poll =>
        asyncEmitChunkImpl(ch, wasSuspendedWith = null, poll = poll)
      }
    }

    /**
     * This is like `Async#asyncCheckAttempt`, except:
     * (1) the immediate result can have a different type,
     * (2) the callback is just for waking up, and
     * (3) a finalizer is mandatory.
     *
     * @see GenWaitListCommon#asyncCheckAttemptEither
     */
    private[this] final def asyncCheckAttemptEitherTuple[F[_], B, C](
      k: (Either[Throwable, Unit] => Unit) => F[Either[(F[Unit], B), C]]
    )(implicit F: Async[F]): F[Either[B, C]] = {
      F.cont(new Cont[F, Unit, Either[B, C]] {
        final override def apply[G[_]](
          implicit G: MonadCancel[G, Throwable]
        ): (Either[Throwable, Unit] => Unit, G[Unit], F ~> G) => G[Either[B, C]] = { (cb, get, lift) =>
          G.uncancelable { poll =>
            lift(k(cb)).flatMap {
              case Right(b) => G.pure(Right(b))
              case Left((fin, b)) => G.onCancel(poll(get), lift(fin)).as(Left(b))
            }
          }
        }
      })
    }

    final def asyncEmitChunkImpl[F[_]](
      ch: Chunk[A],
      wasSuspendedWith: Subscription[A, _],
      poll: F ~> F,
    )(implicit F: AsyncReactive[F]): F[Result] = {
      implicit val FF: Async[F] = F.asyncInst
      // Note: about the `Flag`, see the comment in `GenWaitListCommon#asyncGetImpl`
      F.run(GenWaitList.Flag.mkNew(wasSuspendedWith eq null)).flatMap { asyncFinalizerDone =>
        poll(
          asyncCheckAttemptEitherTuple[F, Subscription[A, _], Result] { cb =>
            F.run(
              isClosed.get.flatMap { isClosed =>
                if (isClosed) {
                  Rxn.pure(Right(Closed))
                } else if (ch.isEmpty) {
                  Rxn.pure(Right(Success))
                } else {
                  subscriptions.get.flatMap { subsMap =>

                    def go(itr: Iterator[Subscription[A, _]], dryRun: Boolean): Rxn[Either[SuspWith[A], Unit]] = {
                      Rxn.unsafe.suspend {
                        if (itr.hasNext) {
                          val subs: Subscription[A, _] = itr.next()
                          _assert(subs ne null)
                          val firstTry = (subs ne wasSuspendedWith)
                          val publish1 = subs.publishChunkOrSuspend(ch, cb, flag = asyncFinalizerDone, firstTry = firstTry)
                          if (dryRun) {
                            // TODO: this is likely quite slow
                            Rxn.unsafe.orElse(
                              publish1.flatMap {
                                case Left(cleanup) =>
                                  val sw = new SuspWith[A](subs, cleanup)
                                  Rxn.pure(Left(sw))
                                case Right(_) =>
                                  Rxn.unsafe.retryStm
                              },
                              go(itr, dryRun = true)
                            )
                          } else {
                            publish1.flatMap {
                              case Left(_) =>
                                impossible("dryRun = false, and got Left")
                              case Right(_) =>
                                go(itr, dryRun = false)
                            }
                          }
                        } else {
                          Rxn.rightUnit
                        }
                      }
                    }

                    Rxn.unsafe.delay(subsMap.valuesIterator).flatMap(go(_, dryRun = true)).flatMap {
                      case Left(suspWith) =>
                        Rxn.pure(Left((
                          F.run(suspWith.cleanup),
                          suspWith.subs
                        )))
                      case Right(()) =>
                        // dry run was successful, now we can do it for real:
                        Rxn.unsafe.delay(subsMap.valuesIterator).flatMap(go(_, dryRun = false)).map {
                          case Left(_) =>
                            impossible("got Left after a successful dryRun")
                          case Right(()) =>
                            Right(Success)
                        }
                    }
                  }
                }
              }
            )
          }
        ).onCancel {
          if (wasSuspendedWith ne null) {
            wasSuspendedWith.publishChunkOrSuspendFallback(asyncFinalizerDone).run
          } else {
            FF.unit
          }
        }
      }.flatMap {
        case Left(subs) =>
          asyncEmitChunkImpl(
            ch,
            wasSuspendedWith = subs,
            poll = poll,
          )
        case Right(result) =>
          FF.pure(result)
      }
    }

    final override def close: Rxn[Result] = {
      isClosed.getAndSet(true).flatMap { wasClosed =>
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
      queue.enqueueOrRetry(ch)
    }

    final def publishChunkOrSuspend(
      ch: Chunk[A],
      cb: Either[Throwable, Unit] => Unit,
      flag: GenWaitList.Flag,
      firstTry: Boolean,
    ): Rxn[Either[Rxn[Unit], Unit]] = {
      queue.enqueueOrSuspend(ch, cb, flag, firstTry)
    }

    final def publishChunkOrSuspendFallback(flag: GenWaitList.Flag): Rxn[Unit] = {
      queue.enqueueOrSuspendFallback(flag)
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
      queue.enqueueSignal(null)
    }

    final def closeInternal[F[_]](implicit F: AsyncReactive[F]): F[Unit] = {
      implicit val FF: Async[F] = F.asyncInst
      F.apply(hub.removeSubscription(this.id)).guarantee(
        awaitShutdown.complete(()).run[F].void
      )
    }
  }

  private[this] final class PubSubBuffer[A](
    val sizeRef: Ref[Int],
    val underlying: UnboundedDeque[Chunk[A]],
    val wl: GenWaitList[Chunk[A]],
  ) {

    final def enqueueSignal(chunk: SignalChunk[A]): Rxn[Unit] = {
      wl.trySetDirectly(chunk).map { ok =>
        _assert(ok)
      }
    }

    final def enqueueOrRetry(chunk: Chunk[A]): Rxn[Success.type] = {
      wl.trySet(chunk).flatMap { ok =>
        if (ok) {
          _axnSuccess
        } else {
          // this means we're in an _async_ `PubSub`, but in this
          // case we can't suspend the fiber; but no problem, we're
          // being called from `emitChunk`, whic expects a retry to
          // handle this case:
          Rxn.unsafe.retryStm
        }
      }
    }

    final def enqueueOrSuspend(
      chunk: Chunk[A],
      cb: Either[Throwable, Unit] => Unit,
      flag: GenWaitList.Flag,
      firstTry: Boolean,
    ): Rxn[Either[Rxn[Unit], Unit]] = {
      wl.asyncSetCb(chunk, cb, firstTry, flag)
    }

    final def enqueueOrSuspendFallback(flag: GenWaitList.Flag): Rxn[Unit] = {
      wl.asyncSetCbFallback(flag)
    }

    final def tryDequeue: Rxn[Option[Chunk[A]]] = {
      wl.tryGet
    }

    final def dequeue[F[_]: AsyncReactive]: F[Chunk[A]] = {
      wl.asyncGet
    }
  }
}
