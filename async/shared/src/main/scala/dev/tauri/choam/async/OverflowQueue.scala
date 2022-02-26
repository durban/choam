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
package async

abstract class OverflowQueue[F[_], A]
  extends UnboundedQueue.WithSize[F, A] {

  def capacity: Int
}

object OverflowQueue {

  def ringBuffer[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    data.RingBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(rb)
    }
  }

  def droppingQueue[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    data.DroppingQueue[A](capacity).flatMapF { dq =>
      F.waitList[A](syncGet = dq.tryDeque, syncSet = dq.enqueue).map { af =>
        new DroppingQueue[F, A](dq, af)
      }
    }
  }

  private[choam] def lazyRingBuffer[F[_], A](capacity: Int)(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    data.RingBuffer.lazyRingBuffer[A](capacity).flatMapF { rb =>
      makeRingBuffer(rb)
    }
  }

  private[this] def makeRingBuffer[F[_], A](underlying: data.RingBuffer[A])(implicit F: AsyncReactive[F]): Axn[OverflowQueue[F, A]] = {
    F.waitList(syncGet = underlying.tryDeque, syncSet = underlying.enqueue).map { af =>
      new RingBuffer(underlying, af)
    }
  }

  private final class RingBuffer[F[_], A](
    buff: data.RingBuffer[A],
    af: WaitList[F, A],
  )(implicit F: AsyncReactive[F]) extends OverflowQueue[F, A] {

    final override def size: F[Int] =
      F.run(buff.size, null : Any)

    final override def toCats =
      new AsyncQueue.CatsQueueAdapter(this)

    final override def capacity =
      buff.capacity

    override def enqueue: Rxn[A, Unit] =
      af.set

    override def tryDeque: Axn[Option[A]] =
      af.syncGet

    override def deque[AA >: A]: F[AA] =
      F.monad.widen(af.get)
  }

  private final class DroppingQueue[F[_], A](
    q: data.DroppingQueue[A],
    af: WaitList[F, A],
  )(implicit F: AsyncReactive[F]) extends OverflowQueue[F, A] {

    final def size: F[Int] =
      F.run(q.size, null : Any)

    final override def toCats =
      new AsyncQueue.CatsQueueAdapter(this)

    final def capacity: Int =
      q.capacity

    final override def tryEnqueue: Rxn[A, Boolean] =
      af.unsafeSetWaitersOrRetry.as(true) + q.tryEnqueue

    final def enqueue: Rxn[A, Unit] =
      af.set

    final def tryDeque: Axn[Option[A]] =
      af.syncGet

    final def deque[AA >: A]: F[AA] =
      F.monad.widen(af.get)
  }
}
