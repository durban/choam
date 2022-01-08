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

import cats.effect.kernel.Resource
import cats.effect.std.{ Queue => CatsQueue }

abstract class UnboundedQueue[F[_], A]
  extends data.Queue[A]
  with AsyncQueueSource[F, A]

object UnboundedQueue {

  abstract class WithSize[F[_], A] extends UnboundedQueue[F, A] {

    def size(implicit F: AsyncReactive[F]): F[Int]

    def toCats(implicit F: AsyncReactive[F]): CatsQueue[F, A] =
      new AsyncQueue.CatsQueueAdapter[F, A](this)

    // FIXME:
    def dequeResource(implicit F: AsyncReactive[F]): Resource[F, F[A]]
  }

  def apply[F[_], A]: Axn[UnboundedQueue[F, A]] = {
    data.Queue.unbounded[A].flatMapF { as =>
      AsyncFrom[F, A](syncGet = as.tryDeque, syncSet = as.enqueue).map { af =>
        new UnboundedQueue[F, A] {
          final override def enqueue: A =#> Unit =
            af.set
          final override def tryDeque: Axn[Option[A]] =
            as.tryDeque
          final override def deque[AA >: A](implicit F: AsyncReactive[F]): F[AA] =
            F.monad.widen(af.get)
        }
      }
    }
  }

  def withSize[F[_], A]: Axn[UnboundedQueue.WithSize[F, A]] = {
    data.Queue.withSize[A].flatMapF { as =>
      AsyncFrom[F, A](syncGet = as.tryDeque, syncSet = as.enqueue).map { af =>
        new UnboundedQueue.WithSize[F, A] {
          final override def enqueue: A =#> Unit =
            af.set
          final override def tryDeque: Axn[Option[A]] =
            as.tryDeque
          final override def deque[AA >: A](implicit F: AsyncReactive[F]): F[AA] =
            F.monad.widen(af.get)
          final override def dequeResource(implicit F: AsyncReactive[F]): Resource[F, F[A]] =
            af.getResource
          final override def size(implicit F: AsyncReactive[F]): F[Int] =
            as.size.run[F]
        }
      }
    }
  }
}
