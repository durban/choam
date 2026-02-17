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
package async

import cats.effect.std.{ Queue => CatsQueue }

import core.{ Rxn, AsyncReactive }

private object OverflowQueueImpl {

  final def ringBuffer[A](capacity: Int): Rxn[AsyncQueue.WithSize[A]] = {
    data.Queue.ringBuffer[A](capacity).flatMap { rb =>
      makeRingBuffer(rb)
    }
  }

  final def ringBuffer[A](capacity: Int, str: AllocationStrategy): Rxn[AsyncQueue.WithSize[A]] = {
    data.Queue.ringBuffer[A](capacity, str).flatMap { rb =>
      makeRingBuffer(rb)
    }
  }

  final def droppingQueue[A](capacity: Int): Rxn[AsyncQueue.WithSize[A]] = {
    data.Queue.dropping[A](capacity).flatMap { dq =>
      GenWaitList[A](dq.poll, dq.offer, dq.peek).map { gwl =>
        new DroppingQueue[A](dq, gwl)
      }
    }
  }

  private[this] final def makeRingBuffer[A](underlying: data.Queue.WithSize[A]): Rxn[AsyncQueue.WithSize[A]] = {
    WaitList(underlying.poll, underlying.add, underlying.peek).map { wl =>
      new RingBuffer(underlying, wl)
    }
  }

  private final class RingBuffer[A](
    buff: data.Queue.WithSize[A],
    wl: WaitList[A],
  ) extends AsyncQueue.UnsealedAsyncQueueWithSize[A] {

    final override def size: Rxn[Int] =
      buff.size

    final override def asCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter(this)

    final override def offer(a: A): Rxn[Boolean] =
      this.add(a).as(true)

    final override def add(a: A): Rxn[Unit] =
      wl.set(a).void

    final override def poll: Rxn[Option[A]] =
      wl.tryGet

    final override def peek: Rxn[Option[A]] =
      wl.tryGetReadOnly

    final override def take[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(wl.asyncGet)
  }

  private final class DroppingQueue[A](
    q: data.Queue.WithSize[A],
    gwl: GenWaitList[A],
  ) extends AsyncQueue.UnsealedAsyncQueueWithSize[A] {

    final override def size: Rxn[Int] =
      q.size

    final override def asCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter(this)

    final override def offer(a: A): Rxn[Boolean] =
      gwl.trySet(a)

    final override def add(a: A): Rxn[Unit] =
      this.offer(a).void

    final override def poll: Rxn[Option[A]] =
      gwl.tryGet

    final override def peek: Rxn[Option[A]] =
      gwl.tryGetReadOnly

    final override def take[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(gwl.asyncGet)
  }
}
