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

sealed trait UnboundedQueue[A]
  extends data.Queue.UnsealedQueue[A]
  with AsyncQueue.UnsealedAsyncQueueSource[A]
  with AsyncQueue.UnsealedBoundedQueueSink[A] {

  final override def enqueue[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] =
    F.apply(this.enqueue, a)
}

object UnboundedQueue {

  sealed trait WithSize[A] extends UnboundedQueue[A] {

    def size: Axn[Int]

    def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A]
  }

  private[choam] trait UnsealedWithSize[A]
    extends WithSize[A]

  final def apply[A]: Axn[UnboundedQueue[A]] = {
    data.Queue.unbounded[A].flatMapF { q =>
      WaitList[A](tryGet = q.tryDeque, syncSet = q.enqueue).map { wl =>
        new UnboundedQueue[A] {
          final override def tryEnqueue: A =#> Boolean =
            this.enqueue.as(true)
          final override def enqueue: A =#> Unit =
            wl.set0
          final override def tryDeque: Axn[Option[A]] =
            q.tryDeque
          final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
            F.monad.widen(wl.asyncGet)
        }
      }
    }
  }

  final def withSize[A]: Axn[UnboundedQueue.WithSize[A]] = {
    data.Queue.unboundedWithSize[A].flatMapF { q =>
      WaitList[A](tryGet = q.tryDeque, syncSet = q.enqueue).map { wl =>
        new UnboundedQueue.WithSize[A] {
          final override def tryEnqueue: A =#> Boolean =
            this.enqueue.as(true)
          final override def enqueue: A =#> Unit =
            wl.set0
          final override def tryDeque: Axn[Option[A]] =
            q.tryDeque
          final override def deque[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
            F.monad.widen(wl.asyncGet)
          final override def size: Axn[Int] =
            q.size
          final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
            new AsyncQueue.CatsQueueAdapter(this)
        }
      }
    }
  }
}
