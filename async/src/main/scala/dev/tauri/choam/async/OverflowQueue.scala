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

import cats.effect.kernel.Resource

abstract class OverflowQueue[F[_], A]
  extends UnboundedQueue.WithSize[F, A] {

  def capacity: Int
}

object OverflowQueue {

  def ringBuffer[F[_], A](capacity: Int): Axn[OverflowQueue[F, A]] = {
    data.RingBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(rb)
    }
  }

  // TODO: test
  private[choam] def lazyRingBuffer[F[_], A](capacity: Int): Axn[OverflowQueue[F, A]] = {
    data.RingBuffer.lazyRingBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(rb)
    }
  }

  private[this] def makeRingBuffer[F[_], A](underlying: data.RingBuffer[A]): Axn[OverflowQueue[F, A]] = {
    AsyncFrom[F, A](syncGet = underlying.tryDeque, syncSet = underlying.enqueue).map { af =>
      new RingBuffer(underlying, af)
    }
  }

  // TODO: def droppingQueue ...

  private final class RingBuffer[F[_], A](
    buff: data.RingBuffer[A],
    af: AsyncFrom[F, A],
  ) extends OverflowQueue[F, A] {

    final override def size(implicit F: AsyncReactive[F]): F[Int] =
      F.run(buff.size, ())

    override def dequeResource(implicit F: AsyncReactive[F]): Resource[F, F[A]] =
      af.getResource

    final override def capacity =
      buff.capacity

    override def enqueue: Rxn[A, Unit] =
      af.set

    override def tryDeque: Axn[Option[A]] =
      af.syncGet

    override def deque[AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(af.get)
  }
}
