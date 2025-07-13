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

import fs2.{ Chunk, Stream }

import core.{ Rxn, AsyncReactive }
import async.AsyncQueue

package object stream {

  // TODO: Channel
  // TODO: SignallingRef
  // TODO: SignallingMapRef
  // TODO: Topic

  final def signallingRef[F[_] : AsyncReactive, A](initial: A): Rxn[RxnSignallingRef[F, A]] =
    RxnSignallingRef[F, A](initial)

  // TODO: do we need these? ---v

  final def fromQueueUnterminated[F[_], A](q: AsyncQueue[A], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminated(new Fs2QueueWrapper(q), limit = limit)(using F.monad)

  final def fromQueueUnterminatedChunk[F[_], A](q: AsyncQueue[Chunk[A]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminatedChunk(new Fs2QueueWrapper(q), limit = limit)(using F.monad)

  final def fromQueueNoneTerminated[F[_], A](q: AsyncQueue[Option[A]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminated(new Fs2QueueWrapper(q), limit = limit)(using F.monad)

  final def fromQueueNoneTerminatedChunk[F[_], A](q: AsyncQueue[Option[Chunk[A]]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminatedChunk(new Fs2QueueWrapper(q), limit = limit)
}
