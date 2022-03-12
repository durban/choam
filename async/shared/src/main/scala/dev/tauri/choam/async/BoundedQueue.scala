/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package async

import cats.effect.std.{ Queue => CatsQueue }

import data.{ Queue, QueueSourceSink, ArrayQueue }

abstract class BoundedQueue[F[_], A]
  extends AsyncQueueSource[F, A]
  with BoundedQueueSink[F, A] {

  def bound: Int

  def toCats: CatsQueue[F, A]

  private[choam] def currentSize: Axn[Int]
}

object BoundedQueue {

  // TODO: Instead of storing promises, could
  // TODO: we store async callbacks directly?
  // TODO: Would it be faster?

  def linked[F[_]: AsyncReactive, A](bound: Int): Axn[BoundedQueue[F, A]] = {
    require(bound > 0)
    (Queue.unbounded[A] * Queue.withRemove[Promise[F, A]] * data.Queue.withRemove[(A, Promise[F, Unit])]).flatMap {
      case ((q, getters), setters) =>
        Ref[Int](0).map { s =>
          new LinkedBoundedQueue[F, A](bound, s, q, getters, setters)
        }
    }
  }

  def array[F[_], A](bound: Int)(implicit F: AsyncReactive[F]): Axn[BoundedQueue[F, A]] = {
    require(bound > 0)
    Queue.boundedArray[A](bound).flatMapF { q =>
      F.genWaitList[A](
        tryGet = q.tryDeque,
        trySet = q.tryEnqueue,
      ).map { gwl =>
        new ArrayBoundedQueue[F, A](bound, q, gwl)
      }
    }
  }

  private final class LinkedBoundedQueue[F[_], A](
    bound: Int,
    s: Ref[Int], // current size
    q: Queue[A],
    getters: Queue.WithRemove[Promise[F, A]],
    setters: Queue.WithRemove[(A, Promise[F, Unit])],
  )(implicit F: AsyncReactive[F]) extends BoundedQueueCommon[F, A](bound, getters, setters) {

    private[choam] final override def currentSize: Axn[Int] =
      s.get

    final override def tryEnqueue: A =#> Boolean = {
      val realEnq = s.updWith[A, Boolean] { (size, a) =>
        if (size < bound) {
          q.enqueue.provide(a).as((size + 1, true))
        } else {
          Rxn.pure((size, false))
        }
      }
      this.tryWaitingEnq + realEnq
    }

    final override def tryDeque: Axn[Option[A]] = {
      q.tryDeque.flatMapF {
        case r @ Some(_) =>
          // size was decremented...
          setters.tryDeque.flatMapF {
            case Some((setterA, setterPromise)) =>
              // ...then incremented
              q.enqueue.provide(setterA) *> (
                setterPromise.complete.provide(()).as(r)
              )
            case None =>
              // ...then nothing
              s.update(_ - 1).as(r)
          }
        case None =>
          // size didn't change
          Rxn.pure(None)
      }
    }

    override def dequeAcq: F[Either[(Promise[F, A], Axn[Unit]), A]] = {
      (Promise[F, A] * q.tryDeque).flatMapF { case (p, dq) =>
        dq match {
          case Some(b) =>
            // size was decremented...
            setters.tryDeque.flatMapF {
              case Some((setterA, setterPromise)) =>
                // ...then incremented
                q.enqueue.provide(setterA).flatTap(
                  setterPromise.complete.provide(()).void
                ).as(Right(b))
              case None =>
                // ...then nothing
                s.update(_ - 1).as(Right(b))
            }
          case None =>
            // size didn't change
            getters.enqueueWithRemover.provide(p).map { remover =>
              Left((p, remover))
            }
        }
      }.run[F]
    }
  }

  private final class ArrayBoundedQueue[F[_], A](
    _bound: Int,
    q: ArrayQueue[A] with QueueSourceSink[A],
    gwl: GenWaitList[F, A],
  )(implicit F: AsyncReactive[F]) extends BoundedQueue[F, A] { self =>

    override def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    override def deque[AA >: A]: F[AA] =
      F.monad.widen(gwl.asyncGet)

    override def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet

    override def enqueue(a: A): F[Unit] =
      gwl.asyncSet(a)

    override def bound: Int =
      _bound

    override def toCats: CatsQueue[F, A] = {
      new CatsQueue[F, A] {
        final override def take: F[A] =
          self.deque
        final override def tryTake: F[Option[A]] =
          F.run(self.tryDeque)
        final override def size: F[Int] =
          F.run(self.currentSize)
        final override def offer(a: A): F[Unit] =
          self.enqueue(a)
        final override def tryOffer(a: A): F[Boolean] =
          F.apply(self.tryEnqueue, a)
      }
    }

    override private[choam] def currentSize: Axn[Int] =
      q.size
  }

  private abstract class BoundedQueueCommon[F[_], A](
    final override val bound: Int,
    protected val getters: Queue.WithRemove[Promise[F, A]],
    protected val setters: Queue.WithRemove[(A, Promise[F, Unit])],
  )(implicit F: AsyncReactive[F]) extends BoundedQueue[F, A] { self =>

    protected def dequeAcq: F[Either[(Promise[F, A], Axn[Unit]), A]]

    /** Partial, retries if no waiting getter! */
    protected def tryWaitingEnq: A =#> true = {
      getters.tryDeque.flatMap {
        case None => Rxn.unsafe.retry
        case Some(p) => p.complete.as(true)
      }
    }

    final override def enqueue(a: A): F[Unit] =
      F.monadCancel.bracket(acquire = this.enqueueAcq(a))(use = this.enqueueUse)(release = this.enqueueRel)

    private[this] def enqueueAcq(a: A): F[Either[(A, Promise[F, Unit], Axn[Unit]), Unit]] = {
      (Promise[F, Unit] * getters.tryDeque).flatMap { case (p, dq) =>
        dq match {
          case Some(getterPromise) =>
            getterPromise.complete.as(Right(()))
          case None =>
            this.tryEnqueue.flatMapF {
              case true =>
                Rxn.pure(Right(()))
              case false =>
                setters.enqueueWithRemover.provide((a, p)).map { remover =>
                  Left((a, p, remover))
                }
            }
        }
      }.apply[F](a)
    }

    private[this] def enqueueRel(r: Either[(A, Promise[F, Unit], Axn[Unit]), Unit])(implicit F: Reactive[F]): F[Unit] = r match {
      case Left((_, _, remover)) => remover.run[F]
      case Right(_) => F.monad.unit
    }

    private[this] def enqueueUse(r: Either[(A, Promise[F, Unit], Axn[Unit]), Unit])(implicit F: Reactive[F]): F[Unit] = r match {
      case Left(ap) => ap._2.get
      case Right(_) => F.monad.unit
    }

    final override def deque[AA >: A]: F[AA] = {
      F.monad.widen(
        F.monadCancel.bracket(acquire = this.dequeAcq)(use = this.dequeUse)(release = this.dequeRel)
      )
    }

    private[this] def dequeRel(r: Either[(Promise[F, A], Axn[Unit]), A])(implicit F: Reactive[F]): F[Unit] = r match {
      case Left((_, remover)) => remover.run[F]
      case Right(_) => F.monad.unit
    }

    private[this] def dequeUse(r: Either[(Promise[F, A], Axn[Unit]), A])(implicit F: Reactive[F]): F[A] = r match {
      case Left((p, _)) => p.get
      case Right(a) => F.monad.pure(a)
    }

    final override def toCats: CatsQueue[F, A] = {
      new CatsQueue[F, A] {
        final override def take: F[A] =
          self.deque
        final override def tryTake: F[Option[A]] =
          F.run(self.tryDeque)
        final override def size: F[Int] =
          F.run(self.currentSize)
        final override def offer(a: A): F[Unit] =
          self.enqueue(a)
        final override def tryOffer(a: A): F[Boolean] =
          F.apply(self.tryEnqueue, a)
      }
    }
  }
}
