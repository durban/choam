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

import fs2.{ Chunk, Stream }
import fs2.concurrent.SignallingRef

import core.{ Rxn, RefLike, AsyncReactive }
import async.AsyncQueue

package object stream {

  // TODO: Channel
  // TODO: SignallingMapRef
  // TODO: Topic

  /**
   * Creates an [[fs2.concurrent.SignallingRef]], which
   * is also readable/writable as a [[dev.tauri.choam.core.Rxn Rxn]],
   * because it has an associated [[dev.tauri.choam.core.RefLike RefLike]]
   * instance, which is returned as the first element of the result
   * tuple.
   *
   * @return a pair of (refLike, signallingRef), which
   *         are "associated with" each other (i.e., use
   *         the same underlying storage).
   */
  final def signallingRef[F[_] : AsyncReactive, A](initial: A): Rxn[(RefLike[A], SignallingRef[F, A])] = {
    signallingRef(initial, AllocationStrategy.Default)
  }

  // TODO: make this public + also an overload with configurable OverflowStrategy
  private[stream] final def signallingRef[F[_] : AsyncReactive, A](
    initial: A,
    str: AllocationStrategy,
  ): Rxn[(RefLike[A], SignallingRef[F, A])] = {
    Fs2SignallingRefWrapper[F, A](initial, str).map { sRef => (sRef.refLike, sRef) }
  }

  final def streamFromQueueUnterminated[F[_], A](q: AsyncQueue.Take[A], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminated(new Fs2QueueWrapper(q), limit = limit)(using F.monad)

  final def streamFromQueueUnterminatedChunks[F[_], A](q: AsyncQueue.Take[Chunk[A]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueUnterminatedChunk(new Fs2QueueWrapper(q), limit = limit)(using F.monad)

  final def streamFromQueueNoneTerminated[F[_], A](q: AsyncQueue.Take[Option[A]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminated(new Fs2QueueWrapper(q), limit = limit)(using F.monad)

  final def streamFromQueueNoneTerminatedChunks[F[_], A](q: AsyncQueue.Take[Option[Chunk[A]]], limit: Int = Int.MaxValue)(implicit F: AsyncReactive[F]): Stream[F, A] =
    Stream.fromQueueNoneTerminatedChunk(new Fs2QueueWrapper(q), limit = limit)
}
