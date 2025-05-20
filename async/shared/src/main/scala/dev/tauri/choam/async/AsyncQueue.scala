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
package async

import cats.effect.std.{ Queue => CatsQueue }

import core.Rxn

sealed trait AsyncQueueSource[+A] extends data.Queue.UnsealedQueueSource[A] {
  def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA]
}

sealed trait BoundedQueueSink[-A] extends data.Queue.UnsealedQueueSink[A] {
  def enqueue[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit]
}

object AsyncQueue {

  private[choam] trait UnsealedAsyncQueueSource[+A]
    extends AsyncQueueSource[A]

  private[choam] trait UnsealedBoundedQueueSink[-A]
    extends BoundedQueueSink[A]

  final def unbounded[A]: Axn[UnboundedQueue[A]] =
    UnboundedQueue[A]

  final def bounded[A](bound: Int): Axn[BoundedQueue[A]] =
    BoundedQueue.array[A](bound)

  final def dropping[A](capacity: Int): Axn[OverflowQueue[A]] =
    OverflowQueue.droppingQueue[A](capacity)

  final def ringBuffer[A](capacity: Int): Axn[OverflowQueue[A]] =
    OverflowQueue.ringBuffer[A](capacity)

  final def unboundedWithSize[A]: Axn[UnboundedQueue.WithSize[A]] =
    UnboundedQueue.withSize[A]

  final def synchronous[A]: Axn[BoundedQueue[A]] = {
    GenWaitList[A](tryGet = Rxn.pure(None), trySet = Rxn.ret(false)).map { gwl =>
      new BoundedQueue.UnsealedBoundedQueue[A] {
        final override def tryDeque: Axn[Option[A]] =
          gwl.tryGet
        final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
          F.monad.widen(gwl.asyncGet)
        final override def tryEnqueue: Rxn[A, Boolean] =
          gwl.trySet0
        final override def enqueue[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] =
          gwl.asyncSet(a)
        final override def bound: Int =
          0
        final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
          new BoundedQueue.CatsQueueFromBoundedQueue[F, A](this)
        final override def size: Axn[Int] =
          Rxn.pure(0)
      }
    }
  }

  private[async] final class CatsQueueAdapter[F[_] : AsyncReactive, A](self: UnboundedQueue.WithSize[A])
    extends CatsQueue[F, A] {

    final override def take: F[A] =
      self.deque
    final override def tryTake: F[Option[A]] =
      self.tryDeque.run[F]
    final override def size: F[Int] =
      self.size.run
    final override def offer(a: A): F[Unit] =
      self.enqueue[F](a)
    final override def tryOffer(a: A): F[Boolean] =
      self.tryEnqueue.apply[F](a)
  }
}
