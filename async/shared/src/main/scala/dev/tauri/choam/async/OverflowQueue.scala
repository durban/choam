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

sealed trait OverflowQueue[A]
  extends UnboundedQueue.UnsealedWithSize[A] {

  def capacity: Int
}

object OverflowQueue {

  final def ringBuffer[A](capacity: Int): Axn[OverflowQueue[A]] = {
    data.Queue.ringBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(capacity, rb)
    }
  }

  final def droppingQueue[A](capacity: Int): Axn[OverflowQueue[A]] = {
    data.Queue.dropping[A](capacity).flatMapF { dq =>
      GenWaitList[A](tryGet = dq.tryDeque, trySet = dq.tryEnqueue).map { gwl =>
        new DroppingQueue[A](capacity, dq, gwl)
      }
    }
  }

  // TODO: do we need this?
  private[choam] final def lazyRingBuffer[A](capacity: Int): Axn[OverflowQueue[A]] = {
    data.Queue.lazyRingBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(capacity, rb)
    }
  }

  private[this] final def makeRingBuffer[A](capacity: Int, underlying: data.Queue.WithSize[A]): Axn[OverflowQueue[A]] = {
    WaitList(tryGet = underlying.tryDeque, syncSet = underlying.enqueue).map { wl =>
      new RingBuffer(capacity, underlying, wl)
    }
  }

  private final class RingBuffer[A](
    final override val capacity: Int,
    buff: data.Queue.WithSize[A],
    wl: WaitList[A],
  ) extends OverflowQueue[A] {

    final override def size: Axn[Int] =
      buff.size

    final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter(this)

    final override def tryEnqueue: Rxn[A, Boolean] =
      this.enqueue.as(true)

    final override def enqueue: Rxn[A, Unit] =
      wl.set0

    final override def tryDeque: Axn[Option[A]] =
      wl.tryGet

    final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(wl.asyncGet)
  }

  private final class DroppingQueue[A](
    final override val capacity: Int,
    q: data.Queue.WithSize[A],
    gwl: GenWaitList[A],
  ) extends OverflowQueue[A] {

    final override def size: Axn[Int] =
      q.size

    final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter(this)

    final override def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet0

    final override def enqueue: Rxn[A, Unit] =
      this.tryEnqueue.void

    final override def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(gwl.asyncGet)
  }
}
