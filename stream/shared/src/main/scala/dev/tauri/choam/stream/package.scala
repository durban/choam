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

import cats.effect.std.{ Queue => CatsQueue }

import fs2.{ Chunk, Stream }

import async.{ UnboundedQueue, AsyncReactive }

package object stream {

  final def signallingRef[F[_] : AsyncReactive, A](initial: A): Axn[RxnSignallingRef[F, A]] =
    RxnSignallingRef[F, A](initial)

  final def fromQueueUnterminated[F[_], A](q: UnboundedQueue[F, A], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminated(new Fs2QueueWrapper(q), limit = limit)(F.monad)

  final def fromQueueUnterminatedChunk[F[_], A](q: UnboundedQueue[F, Chunk[A]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminatedChunk(new Fs2QueueWrapper(q), limit = limit)(F.monad)

  final def fromQueueNoneTerminated[F[_], A](q: UnboundedQueue[F, Option[A]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminated(new Fs2QueueWrapper(q), limit = limit)(F.monad)

  final def fromQueueNoneTerminatedChunk[F[_], A](q: UnboundedQueue[F, Option[Chunk[A]]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminatedChunk(new Fs2QueueWrapper(q), limit = limit)

  // TODO: this could work with BoundedQueue too
  @nowarn
  private final class Fs2QueueWrapper[F[_], A](
    self: UnboundedQueue[F, A]
  )(implicit F: AsyncReactive[F]) extends CatsQueue[F, A] {
    final override def take: F[A] =
      self.deque
    final override def tryTake: F[Option[A]] =
      self.tryDeque.run[F]
    final override def size: F[Int] =
      F.monad.pure(0) // FS2 doesn't really need `size`, so we cheat
    final override def offer(a: A): F[Unit] =
      self.enqueue[F](a)
    final override def tryOffer(a: A): F[Boolean] =
      self.enqueue.as(true).apply[F](a)
  }
}
