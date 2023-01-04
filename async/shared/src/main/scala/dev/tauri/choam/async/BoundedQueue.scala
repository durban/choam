/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import data.{ Queue, QueueSourceSink }

abstract class BoundedQueue[F[_], A]
  extends AsyncQueueSource[F, A]
  with BoundedQueueSink[F, A]
  with QueueSourceSink[A] {

  def bound: Int

  def toCats: CatsQueue[F, A]

  def size: Axn[Int]
}

object BoundedQueue {

  def linked[F[_], A](bound: Int)(implicit F: AsyncReactive[F]): Axn[BoundedQueue[F, A]] = {
    require(bound > 0)
    (Queue.unbounded[A] * Ref[Int](0)).flatMapF {
      case (q, size) =>
        F.genWaitList[A](
          tryGet = q.tryDeque.flatMapF { res =>
            if (res.nonEmpty) size.update(_ - 1).as(res)
            else Rxn.pure(res)
          },
          trySet = size.updWith[A, Boolean] { (s, a) =>
            if (s < bound) q.enqueue.provide(a).as((s + 1, true))
            else Rxn.pure((s, false))
          },
        ).map { gwl =>
          new LinkedBoundedQueue[F, A](bound, size, gwl)
        }
    }
  }

  def array[F[_], A](bound: Int)(implicit F: AsyncReactive[F]): Axn[BoundedQueue[F, A]] = {
    require(bound > 0)
    Queue.dropping[A](bound).flatMapF { q =>
      F.genWaitList[A](
        tryGet = q.tryDeque,
        trySet = q.tryEnqueue,
      ).map { gwl =>
        new ArrayBoundedQueue[F, A](bound, q, gwl)
      }
    }
  }

  private final class LinkedBoundedQueue[F[_], A](
    _bound: Int,
    s: Ref[Int], // current size
    gwl: GenWaitList[F, A],
  )(implicit F: AsyncReactive[F]) extends BoundedQueue[F, A] {

    override def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    override def deque[AA >: A]: F[AA] =
      F.monad.widen(gwl.asyncGet)

    override def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet

    override def enqueue(a: A): F[Unit] =
      gwl.asyncSet(a)

    override def bound: Int =
      _bound

    override def toCats: CatsQueue[F, A] = {
      new CatsQueueFromBoundedQueue(this)
    }

    override def size: Axn[Int] =
      s.get
  }

  private final class ArrayBoundedQueue[F[_], A](
    _bound: Int,
    q: Queue.WithSize[A],
    gwl: GenWaitList[F, A],
  )(implicit F: AsyncReactive[F]) extends BoundedQueue[F, A] { self =>

    override def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    override def deque[AA >: A]: F[AA] =
      F.monad.widen(gwl.asyncGet)

    override def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet

    override def enqueue(a: A): F[Unit] =
      gwl.asyncSet(a)

    override def bound: Int =
      _bound

    override def toCats: CatsQueue[F, A] = {
      new CatsQueueFromBoundedQueue[F, A](this)(F)
    }

    override def size: Axn[Int] =
      q.size
  }

  private[async] final class CatsQueueFromBoundedQueue[F[_], A](
    self: BoundedQueue[F, A]
  )(implicit F: Reactive[F]) extends CatsQueue[F, A] {
    final override def take: F[A] =
      self.deque
    final override def tryTake: F[Option[A]] =
      F.run(self.tryDeque)
    final override def size: F[Int] =
      F.run(self.size)
    final override def offer(a: A): F[Unit] =
      self.enqueue(a)
    final override def tryOffer(a: A): F[Boolean] =
      F.apply(self.tryEnqueue, a)
  }
}
