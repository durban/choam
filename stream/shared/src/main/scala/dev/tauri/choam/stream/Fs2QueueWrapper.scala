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

import cats.effect.std.{ Queue => CatsQueue }

import async.{ AsyncReactive, AsyncQueueSource, BoundedQueueSink }

private final class Fs2QueueWrapper[F[_], A](
  self: AsyncQueueSource[A] with BoundedQueueSink[A],
)(implicit F: AsyncReactive[F]) extends CatsQueue[F, A] {
  final override def take: F[A] =
    self.deque
  final override def tryTake: F[Option[A]] =
    self.tryDeque.run[F]
  final override def size: F[Int] =
    F.asyncInst.raiseError(new AssertionError) // FS2 doesn't really need `size`, so we cheat
  final override def offer(a: A): F[Unit] =
    self.enqueue[F](a)
  final override def tryOffer(a: A): F[Boolean] =
    F.apply(self.tryEnqueue, a)
}
