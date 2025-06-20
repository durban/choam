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

import core.{ Rxn, Axn, Ref, AsyncReactive }
import data.Queue

sealed trait BoundedQueue[A]
  extends AsyncQueue.UnsealedAsyncQueueSource[A]
  with AsyncQueue.UnsealedBoundedQueueSink[A]
  with Queue.UnsealedQueueSourceSink[A] {

  def bound: Int

  def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A]

  def size: Axn[Int]
}

object BoundedQueue {

  private[choam] trait UnsealedBoundedQueue[A]
    extends BoundedQueue[A]

  final def linked[A](bound: Int): Axn[BoundedQueue[A]] = {
    require(bound > 0)
    (Queue.unbounded[A] * Ref.unpadded[Int](0)).flatMapF {
      case (q, size) =>
        GenWaitList[A](
          q.tryDeque.flatMapF { res =>
            if (res.nonEmpty) size.update(_ - 1).as(res)
            else Rxn.pure(res)
          },
          size.updWith[A, Boolean] { (s, a) =>
            if (s < bound) q.enqueue.provide(a).as((s + 1, true))
            else Rxn.pure((s, false))
          },
        ).map { gwl =>
          new LinkedBoundedQueue[A](bound, size, gwl)
        }
    }
  }

  final def array[A](bound: Int): Axn[BoundedQueue[A]] = {
    require(bound > 0)
    Queue.dropping[A](bound).flatMapF { q =>
      GenWaitList[A](
        q.tryDeque,
        q.tryEnqueue,
      ).map { gwl =>
        new ArrayBoundedQueue[A](bound, q, gwl)
      }
    }
  }

  private final class LinkedBoundedQueue[A](
    _bound: Int,
    s: Ref[Int], // current size
    gwl: GenWaitList[A],
  ) extends BoundedQueue[A] {

    final override def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(gwl.asyncGet)

    final override def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet0

    final override def enqueue[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] =
      gwl.asyncSet(a)

    final override def bound: Int =
      _bound

    final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] = {
      new CatsQueueFromBoundedQueue(this)
    }

    final override def size: Axn[Int] =
      s.get
  }

  private final class ArrayBoundedQueue[A](
    _bound: Int,
    q: Queue.WithSize[A],
    gwl: GenWaitList[A],
  ) extends BoundedQueue[A] { self =>

    final override def tryDeque: Axn[Option[A]] =
      gwl.tryGet

    final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
      F.monad.widen(gwl.asyncGet)

    final override def tryEnqueue: Rxn[A, Boolean] =
      gwl.trySet0

    final override def enqueue[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] =
      gwl.asyncSet(a)

    final override def bound: Int =
      _bound

    final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] = {
      new CatsQueueFromBoundedQueue[F, A](this)(using F)
    }

    final override def size: Axn[Int] =
      q.size
  }

  private[async] final class CatsQueueFromBoundedQueue[F[_], A](
    self: BoundedQueue[A]
  )(implicit F: AsyncReactive[F]) extends CatsQueue[F, A] {
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
