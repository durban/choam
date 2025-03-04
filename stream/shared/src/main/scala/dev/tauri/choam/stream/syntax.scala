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
package stream

import scala.language.implicitConversions

import fs2.{ Stream, Chunk }

import async.{ AsyncReactive, UnboundedQueue }

object syntax extends StreamSyntax

trait StreamSyntax extends StreamSyntax0

private[stream] sealed trait StreamSyntax0 extends StreamSyntax1 {
  implicit def asyncQueueChunkNoneTerminatedSyntax[A](q: UnboundedQueue[Option[Chunk[A]]]): AsyncQueueChunkNoneTerminatedSyntax[A] =
    new AsyncQueueChunkNoneTerminatedSyntax(q)
}

private[stream] sealed trait StreamSyntax1 extends StreamSyntax2 {
  implicit def asyncQueueChunkSyntax[A](q: UnboundedQueue[Chunk[A]]): AsyncQueueChunkSyntax[A] =
    new AsyncQueueChunkSyntax(q)
}

private[stream] sealed trait StreamSyntax2 extends StreamSyntax3 {
  implicit def asyncQueueNoneTerminatedSyntax[A](q: UnboundedQueue[Option[A]]): AsyncQueueNoneTerminatedSyntax[A] =
    new AsyncQueueNoneTerminatedSyntax(q)
}

private[stream] sealed trait StreamSyntax3 {
  implicit def asyncQueueSyntax[A](q: UnboundedQueue[A]): AsyncQueueSyntax[A] =
    new AsyncQueueSyntax(q)
}

final class AsyncQueueSyntax[A](private val self: UnboundedQueue[A])
  extends AnyVal {

  final def stream[F[_]](implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueUnterminated(self)
}

final class AsyncQueueChunkSyntax[A](private val self: UnboundedQueue[Chunk[A]])
  extends AnyVal {

  final def streamFromChunks[F[_]](implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueUnterminatedChunk(self)
}

final class AsyncQueueNoneTerminatedSyntax[A](private val self: UnboundedQueue[Option[A]])
  extends AnyVal {

  final def streamNoneTerminated[F[_]](implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueNoneTerminated(self)
}

final class AsyncQueueChunkNoneTerminatedSyntax[A](private val self: UnboundedQueue[Option[Chunk[A]]])
  extends AnyVal {

  final def streamFromChunksNoneTerminated[F[_]](implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueNoneTerminatedChunk(self)
}
