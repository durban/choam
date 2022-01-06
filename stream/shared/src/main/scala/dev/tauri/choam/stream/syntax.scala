/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
  implicit def asyncQueueChunkNoneTerminatedSyntax[F[_], A](q: UnboundedQueue[F, Option[Chunk[A]]]): AsyncQueueChunkNoneTerminatedSyntax[F, A] =
    new AsyncQueueChunkNoneTerminatedSyntax(q)
}

private[stream] sealed trait StreamSyntax1 extends StreamSyntax2 {
  implicit def asyncQueueChunkSyntax[F[_], A](q: UnboundedQueue[F, Chunk[A]]): AsyncQueueChunkSyntax[F, A] =
    new AsyncQueueChunkSyntax(q)
}

private[stream] sealed trait StreamSyntax2 extends StreamSyntax3 {
  implicit def asyncQueueNoneTerminatedSyntax[F[_], A](q: UnboundedQueue[F, Option[A]]): AsyncQueueNoneTerminatedSyntax[F, A] =
    new AsyncQueueNoneTerminatedSyntax(q)
}

private[stream] sealed trait StreamSyntax3 {
  implicit def asyncQueueSyntax[F[_], A](q: UnboundedQueue[F, A]): AsyncQueueSyntax[F, A] =
    new AsyncQueueSyntax(q)
}

final class AsyncQueueSyntax[F[_], A](private val self: UnboundedQueue[F, A])
  extends AnyVal {

  final def stream(implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueUnterminated(self)
}

final class AsyncQueueChunkSyntax[F[_], A](private val self: UnboundedQueue[F, Chunk[A]])
  extends AnyVal {

  final def streamFromChunks(implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueUnterminatedChunk(self)
}

final class AsyncQueueNoneTerminatedSyntax[F[_], A](private val self: UnboundedQueue[F, Option[A]])
  extends AnyVal {

  final def streamNoneTerminated(implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueNoneTerminated(self)
}

final class AsyncQueueChunkNoneTerminatedSyntax[F[_], A](private val self: UnboundedQueue[F, Option[Chunk[A]]])
  extends AnyVal {

  final def streamFromChunksNoneTerminated(implicit F: AsyncReactive[F]): Stream[F, A] =
    fromQueueNoneTerminatedChunk(self)
}
