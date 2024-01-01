/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

trait AsyncQueueSource[F[_], +A] extends data.QueueSource[A] {
  def deque[AA >: A]: F[AA]
}

trait BoundedQueueSink[F[_], -A] extends data.QueueSink[A] {
  def enqueue(a: A): F[Unit]
}

object AsyncQueue {

  def unbounded[F[_] : AsyncReactive, A]: Axn[UnboundedQueue[F, A]] =
    UnboundedQueue[F, A]

  def bounded[F[_], A](bound: Int)(implicit F: AsyncReactive[F]): Axn[BoundedQueue[F, A]] =
    BoundedQueue.array[F, A](bound)

  def dropping[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] =
    OverflowQueue.droppingQueue[F, A](capacity)

  def ringBuffer[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] =
    OverflowQueue.ringBuffer[F, A](capacity)

  def unboundedWithSize[F[_], A](implicit F: AsyncReactive[F]): Axn[UnboundedQueue.WithSize[F, A]] =
    UnboundedQueue.withSize[F, A]

  def synchronous[F[_], A](implicit F: AsyncReactive[F]): Axn[BoundedQueue[F, A]] = {
    F.genWaitList[A](tryGet = Rxn.pure(None), trySet = Rxn.ret(false)).map { gwl =>
      new BoundedQueue[F, A] {
        final def tryDeque: Axn[Option[A]] =
          gwl.tryGet
        final def deque[AA >: A]: F[AA] =
          F.monad.widen(gwl.asyncGet)
        final def tryEnqueue: Rxn[A, Boolean] =
          gwl.trySet
        final def enqueue(a: A): F[Unit] =
          gwl.asyncSet(a)
        final def bound: Int =
          0
        final def toCats: CatsQueue[F, A] =
          new BoundedQueue.CatsQueueFromBoundedQueue[F, A](this)
        final def size: Axn[Int] =
          Rxn.pure(0)
      }
    }
  }

  private[async] final class CatsQueueAdapter[F[_] : AsyncReactive, A](self: UnboundedQueue.WithSize[F, A])
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
      self.tryEnqueue.apply[F](a)
  }
}
