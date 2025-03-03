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

sealed trait UnboundedQueue[F[_], A]
  extends data.Queue.UnsealedQueue[A]
  with AsyncQueue.UnsealedAsyncQueueSource[F, A]

object UnboundedQueue {

  sealed trait WithSize[F[_], A] extends UnboundedQueue[F, A] {

    def size: F[Int]

    def toCats: CatsQueue[F, A]
  }

  private[choam] trait UnsealedWithSize[F[_], A]
    extends WithSize[F, A]

  final def apply[F[_], A](implicit F: AsyncReactive[F]): Axn[UnboundedQueue[F, A]] = {
    data.Queue.unbounded[A].flatMapF { q =>
      WaitList[A](tryGet = q.tryDeque, syncSet = q.enqueue).map { wl =>
        new UnboundedQueue[F, A] {
          final override def tryEnqueue: A =#> Boolean =
            this.enqueue.as(true)
          final override def enqueue: A =#> Unit =
            wl.set0
          final override def tryDeque: Axn[Option[A]] =
            q.tryDeque
          final override def deque[AA >: A]: F[AA] =
            F.monad.widen(wl.asyncGet)
        }
      }
    }
  }

  final def withSize[F[_], A](implicit F: AsyncReactive[F]): Axn[UnboundedQueue.WithSize[F, A]] = {
    data.Queue.unboundedWithSize[A].flatMapF { q =>
      WaitList[A](tryGet = q.tryDeque, syncSet = q.enqueue).map { wl =>
        new UnboundedQueue.WithSize[F, A] {
          final override def tryEnqueue: A =#> Boolean =
            this.enqueue.as(true)
          final override def enqueue: A =#> Unit =
            wl.set0
          final override def tryDeque: Axn[Option[A]] =
            q.tryDeque
          final override def deque[AA >: A]: F[AA] =
            F.monad.widen(wl.asyncGet)
          final override def size: F[Int] =
            q.size.run[F]
          final override def toCats: CatsQueue[F, A] =
            new AsyncQueue.CatsQueueAdapter(this)
        }
      }
    }
  }
}
