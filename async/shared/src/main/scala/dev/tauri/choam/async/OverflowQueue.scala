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

sealed trait OverflowQueue[F[_], A]
  extends UnboundedQueue.UnsealedWithSize[F, A] {

  def capacity: Int
}

object OverflowQueue {

  final def ringBuffer[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    data.Queue.ringBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(capacity, rb)
    }
  }

  final def droppingQueue[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    data.Queue.dropping[A](capacity).flatMapF { dq =>
      GenWaitList[A](tryGet = dq.tryDeque, trySet = dq.tryEnqueue).map { gwl =>
        new DroppingQueue[F, A](capacity, dq, gwl)
      }
    }
  }

  // TODO: do we need this?
  private[choam] final def lazyRingBuffer[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    data.Queue.lazyRingBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(capacity, rb)
    }
  }

  private[this] final def makeRingBuffer[F[_], A](capacity: Int, underlying: data.Queue.WithSize[A])(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    WaitList(tryGet = underlying.tryDeque, syncSet = underlying.enqueue).map { wl =>
      new RingBuffer(capacity, underlying, wl)
    }
  }

  private final class RingBuffer[F[_], A](
    final override val capacity: Int,
    buff: data.Queue.WithSize[A],
    wl: WaitList[A],
  )(implicit F: AsyncReactive[F]) extends OverflowQueue[F, A] {

    final override def size: F[Int] =
      F.run(buff.size)

    final override def toCats: CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter(this)

    final override def tryEnqueue: Rxn[A, Boolean] =
      this.enqueue.as(true)

    final override def enqueue: Rxn[A, Unit] =
      wl.set0

    final override def tryDeque: Axn[Option[A]] =
      wl.tryGet

    final override def deque[AA >: A]: F[AA] =
      F.monad.widen(wl.asyncGet)
  }

  private final class DroppingQueue[F[_], A](
    final override val capacity: Int,
    q: data.Queue.WithSize[A],
    gwl: GenWaitList[A],
  )(implicit F: AsyncReactive[F]) extends OverflowQueue[F, A] {

    final def size: F[Int] =
      F.run(q.size)

    final def toCats: CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter(this)

    final def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet0

    final def enqueue: Rxn[A, Unit] =
      this.tryEnqueue.void

    final def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    final def deque[AA >: A]: F[AA] =
      F.monad.widen(gwl.asyncGet)
  }
}
