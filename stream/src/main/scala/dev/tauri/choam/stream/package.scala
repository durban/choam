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

import cats.effect.std.{ Queue => CatsQueue }

import fs2.{ Stream, Chunk }

import async.{ AsyncQueue, AsyncReactive }

package object stream {

  def fromQueueUnterminated[F[_], A](q: AsyncQueue[F, A])(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminated(new Fs2QueueWrapper(q))(F.monad)

  def fromQueueUnterminatedChunk[F[_], A](q: AsyncQueue[F, Chunk[A]])(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminatedChunk(new Fs2QueueWrapper(q))(F.monad)

  def fromQueueNoneTerminated[F[_], A](q: AsyncQueue[F, Option[A]])(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminated(new Fs2QueueWrapper(q))(F.monad)

  def fromQueueNoneTerminatedChunk[F[_], A](q: AsyncQueue[F, Option[Chunk[A]]])(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminatedChunk(new Fs2QueueWrapper(q))

  @nowarn
  private final class Fs2QueueWrapper[F[_], A](
    self: AsyncQueue[F, A]
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
