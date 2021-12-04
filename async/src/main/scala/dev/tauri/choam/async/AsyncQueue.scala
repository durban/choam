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

import cats.effect.std.{ Queue => CatsQueue }

trait AsyncQueueSource[F[_], +A] extends data.QueueSource[A] {
  def deque[AA >: A](implicit F: AsyncReactive[F]): F[AA]
}

trait BoundedQueueSink[F[_], -A] extends data.QueueSink[A] {
  def enqueue(a: A)(implicit F: AsyncReactive[F]): F[Unit]
}

object AsyncQueue {

  def unbounded[F[_], A]: Axn[UnboundedQueue[F, A]] =
    UnboundedQueue[F, A]

  def bounded[F[_], A](bound: Int): Axn[BoundedQueue[F, A]] =
    BoundedQueue[F, A](bound)

  def dropping[F[_], A](@unused capacity: Int): Axn[OverflowQueue[F, A]] =
    sys.error("TODO")

  def ringBuffer[F[_], A](capacity: Int): Axn[OverflowQueue[F, A]] =
    OverflowQueue.ringBuffer[F, A](capacity)

  def unboundedWithSize[F[_], A]: Axn[UnboundedQueue.WithSize[F, A]] =
    UnboundedQueue.withSize[F, A]

  private[choam] final class CatsQueueAdapter[F[_] : AsyncReactive, A](self: UnboundedQueue.WithSize[F, A])
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
