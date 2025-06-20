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

import core.{ Axn, AsyncReactive }

sealed trait AsyncQueueSource[+A] extends data.Queue.UnsealedQueueSource[A] {
  def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] // TODO:0.5: should be called `dequeue`
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

  // TODO: final def synchronous[A]: Axn[BoundedQueue[A]] = ...
  // TODO:
  // TODO: Providing a synchronous queue which has operations
  // TODO: in `Rxn` seems fundamentally impossible. Let's say
  // TODO: we have something like this:
  // TODO: trait SynchronousQueue[A] {
  // TODO:   def take[F[_]]: F[A]
  // TODO:   def offer[F[_]](a: A): F[Unit]
  // TODO:   def tryTake: Axn[Option[A]]
  // TODO:   def tryOffer(a: A): Axn[Boolean]
  // TODO: }
  // TODO: If `tryOffer` returns `true`, that should mean
  // TODO: that someone will definitely receive the item.
  // TODO: But, even if there is a waiting taker, we can't
  // TODO: be sure that's true. The taker can be cancelled,
  // TODO: in which case, we don't know what to do with the
  // TODO: item. (Bounded queues have the underlying queue,
  // TODO: which can store the item.)
  // TODO: What could work is this:
  // TODO: trait SynchronousQueue[A] {
  // TODO:   def take[F[_]]: F[A]
  // TODO:   def offer[F[_]](a: A): F[Unit]
  // TODO: }
  // TODO: But this is basically `cats.effect.std.Queue.synchronous`.
  // TODO: We could implement it with `Rxn` internally. Would
  // TODO: that have a performance advantage? Maybe if contention
  // TODO: is high (because the CE queue uses an single Ref)?

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
