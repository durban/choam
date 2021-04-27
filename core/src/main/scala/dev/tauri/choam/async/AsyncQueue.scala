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
package async

import cats.effect.kernel.Resource
import cats.effect.std.{ Queue => CatsQueue }

abstract class AsyncQueue[F[_], A] { self =>
  def enqueue: A =#> Unit
  def tryDeque: Axn[Option[A]]
  def deque(implicit F: Reactive.Async[F]): F[A]
}

object AsyncQueue {

  abstract class WithSize[F[_], A] extends AsyncQueue[F, A] {

    def size(implicit F: Reactive.Async[F]): F[Int]

    // TODO: could this return simply `CatsQueue[F, A]`?
    def toCats(implicit F: Reactive.Async[F]): F[CatsQueue[F, A]] = {
      val cq = new AsyncQueue.CatsQueueAdapter[F, A](this)
      F.monad.pure(cq)
    }

    // FIXME:
    def dequeResource(implicit F: Reactive.Async[F]): Resource[F, F[A]]
  }

  def primitive[F[_], A]: Axn[AsyncQueue[F, A]] = {
    (Queue[A] * Queue.withRemove[Promise[F, A]]) >>> (
      Axn.delay { case (as, waiters) => new AsyncQueuePrim(as, waiters) }
    )
  }

  def derived[F[_], A]: Axn[AsyncQueue[F, A]] = {
    Queue[A].flatMap { as =>
      AsyncFrom[F, Any, A](syncGet = as.tryDeque, syncSet = as.enqueue).map { af =>
        new AsyncQueue[F, A] {
          final override def enqueue: A =#> Unit =
            af.set
          final override def tryDeque: Axn[Option[A]] =
            as.tryDeque
          final override def deque(implicit F: Reactive.Async[F]): F[A] =
            af.get(())
        }
      }
    }
  }

  def withSize[F[_], A]: Axn[AsyncQueue.WithSize[F, A]] = {
    Queue.withSize[A].flatMap { as =>
      AsyncFrom[F, Any, A](syncGet = as.tryDeque, syncSet = as.enqueue).map { af =>
        new WithSize[F, A] {
          final override def enqueue: A =#> Unit =
            af.set
          final override def tryDeque: Axn[Option[A]] =
            as.tryDeque
          final override def deque(implicit F: Reactive.Async[F]): F[A] =
            af.get(())
          final override def dequeResource(implicit F: Reactive.Async[F]): Resource[F, F[A]] =
            af.getResource(())
          final override def size(implicit F: Reactive.Async[F]): F[Int] =
            as.size.run[F]
        }
      }
    }
  }

  private final class AsyncQueuePrim[F[_], A](
    q: Queue[A],
    waiters: Queue.WithRemove[Promise[F, A]]
  ) extends AsyncQueue[F, A] {

    final override def enqueue: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None => this.q.enqueue
        case Some(p) => p.complete.discard
      }
    }

    final override def tryDeque: Axn[Option[A]] =
      this.q.tryDeque

    final override def deque(implicit F: Reactive.Async[F]): F[A] = {
      val acq = Promise[F, A].flatMap { p =>
        this.q.tryDeque.flatMap {
          case Some(a) => Axn.pure(Right(a))
          case None => this.waiters.enqueue.provide(p).as(Left(p))
        }
      }.run[F]
      val rel: (Either[Promise[F, A], A] => F[Unit]) = {
        case Left(p) => this.waiters.remove.discard[F](p)
        case Right(_) => F.monadCancel.unit
      }
      F.monadCancel.bracket(acquire = acq) {
        case Left(p) => p.get
        case Right(a) => F.monadCancel.pure(a)
      } (release = rel)
    }
  }

  private final class CatsQueueAdapter[F[_] : Reactive.Async, A](self: WithSize[F, A])
    extends CatsQueue[F, A] {

    final override def take: F[A] =
      self.deque
    final override def tryTake: F[Option[A]] =
      self.tryDeque.run[F]
    final override def size: F[Int] =
      self.size
    final override def offer(a: A): F[Unit] =
      self.enqueue[F](a)
    final override def tryOffer(a: A): F[Boolean] =
      self.enqueue.as(true).apply[F](a)
  }
}
