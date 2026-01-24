/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import core.{ Rxn, AsyncReactive }

sealed trait AsyncQueue[A]
  extends data.Queue.UnsealedQueue[A]
  with AsyncQueue.SourceSink[A] {

  final override def put[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] =
    F.run(this.add(a))
}

/**
 * Various asynchronous queues
 *
 * Adds asynchronous variants to the methods of
 * [[dev.tauri.choam.data.Queue$ Queue]] (see the last column
 * of the table below). These operations have a result
 * type in an asynchronous `F`, and may be fiber-blocking.
 * For example, asynchronously removing an element from
 * an empty queue fiber-blocks until the queue is non-empty
 * (or until the fiber is cancelled).
 *
 * Method summary of the various operations:
 *
 * |         | `Rxn` (may fail)    | `Rxn` (succeeds) | `F` (may block)        |
 * |---------|---------------------|------------------|------------------------|
 * | insert  | `Queue.Offer#offer` | `Queue.Add#add`  | `AsyncQueue.Put#put`   |
 * | remove  | `Queue.Poll#poll`   | -                | `AsyncQueue.Take#take` |
 * | examine | `Queue.Poll#peek`   | -                | -                      |
 *
 * @see [[dev.tauri.choam.data.Queue$ Queue]]
 *      for the synchronous methods (all except
 *      the last column of this table)
 */
object AsyncQueue {

  sealed trait Take[+A] extends data.Queue.UnsealedQueuePoll[A] {
    // TODO: add InvariantSyntax (to be able to call it like `.take[F]`)
    def take[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA]
    // TODO: asCats : QueueSource (but: needs size)
    // TODO: Functor instance
  }

  sealed trait Put[-A] extends data.Queue.UnsealedQueueOffer[A] {
    def put[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit]
    // TODO: asCats : QueueSink
    // TODO: Covariant instance
  }

  sealed trait SourceSink[A]
    extends data.Queue.UnsealedQueueSourceSink[A]
    with AsyncQueue.Take[A]
    with AsyncQueue.Put[A]

  sealed trait WithSize[A]
    extends AsyncQueue[A] {

    def size: Rxn[Int]

    def asCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A]
  }

  final def unbounded[A]: Rxn[AsyncQueue[A]] =
    UnboundedQueueImpl[A]

  final def bounded[A](bound: Int): Rxn[AsyncQueue.SourceSink[A]] =
    BoundedQueueImpl.array[A](bound) // TODO: technically, this is already "WithSize" (see also below)

  final def dropping[A](capacity: Int): Rxn[AsyncQueue.WithSize[A]] =
    OverflowQueueImpl.droppingQueue[A](capacity)

  final def ringBuffer[A](capacity: Int): Rxn[AsyncQueue.WithSize[A]] =
    OverflowQueueImpl.ringBuffer[A](capacity)

  final def unboundedWithSize[A]: Rxn[AsyncQueue.WithSize[A]] =
    UnboundedQueueImpl.withSize[A]

  // TODO: boundedWithSize : AsyncQueue.SourceSinkWithSize? (see below)

  private[choam] trait UnsealedAsyncQueueTake[+A]
    extends AsyncQueue.Take[A]

  private[choam] trait UnsealedAsyncQueuePut[-A]
    extends AsyncQueue.Put[A]

  private[choam] trait UnsealedAsyncQueueSourceSink[A]
    extends AsyncQueue.SourceSink[A]

  private[choam] trait UnsealedAsyncQueue[A]
    extends AsyncQueue[A]

  private[choam] trait UnsealedAsyncQueueWithSize[A]
    extends AsyncQueue.WithSize[A]

  private[choam] sealed trait SourceSinkWithSize[A] // TODO: do we want this public? (see above)
    extends AsyncQueue.SourceSink[A] {

    def asCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A]

    def size: Rxn[Int]
  }

  private[choam] trait UnsealedAsyncQueueSourceSinkWithSize[A]
    extends AsyncQueue.SourceSinkWithSize[A]

  // TODO: final def synchronous[A]: Rxn[BoundedQueue[A]] = ...
  // TODO:
  // TODO: Providing a synchronous queue which has operations
  // TODO: in `Rxn` seems fundamentally impossible. Let's say
  // TODO: we have something like this:
  // TODO: trait SynchronousQueue[A] {
  // TODO:   def take[F[_]]: F[A]
  // TODO:   def offer[F[_]](a: A): F[Unit]
  // TODO:   def tryTake: Rxn[Option[A]]
  // TODO:   def tryOffer(a: A): Rxn[Boolean]
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
  // TODO: is high (because the CE queue uses a single Ref)?

  private[async] final class CatsQueueAdapter[F[_] : AsyncReactive, A](self: AsyncQueue.WithSize[A])
    extends CatsQueue[F, A] {

    final override def take: F[A] =
      self.take
    final override def tryTake: F[Option[A]] =
      self.poll.run[F]
    final override def size: F[Int] =
      self.size.run
    final override def offer(a: A): F[Unit] =
      self.put[F](a)
    final override def tryOffer(a: A): F[Boolean] =
      self.offer(a).run[F]
  }
}
