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

import core.{ Rxn, AsyncReactive }

private object UnboundedQueueImpl {

  final def apply[A]: Rxn[AsyncQueue[A]] = {
    data.Queue.unbounded[A].flatMap { q =>
      WaitList[A](q.poll, q.add).map { wl =>
        new AsyncQueue.UnsealedAsyncQueue[A] {
          final override def offer(a: A): Rxn[Boolean] =
            this.add(a).as(true)
          final override def add(a: A): Rxn[Unit] =
            wl.set0(a).void
          final override def poll: Rxn[Option[A]] =
            wl.tryGet
          final override def take[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
            F.monad.widen(wl.asyncGet)
        }
      }
    }
  }

  final def withSize[A]: Rxn[AsyncQueue.WithSize[A]] = {
    data.Queue.unboundedWithSize[A].flatMap { q =>
      WaitList[A](q.poll, q.add).map { wl =>
        new AsyncQueue.UnsealedAsyncQueueWithSize[A] {
          final override def offer(a: A): Rxn[Boolean] =
            this.add(a).as(true)
          final override def add(a: A): Rxn[Unit] =
            wl.set0(a).void
          final override def poll: Rxn[Option[A]] =
            wl.tryGet
          final override def take[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] =
            F.monad.widen(wl.asyncGet)
          final override def size: Rxn[Int] =
            q.size
          final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsQueue[F, A] =
            new AsyncQueue.CatsQueueAdapter(this)
        }
      }
    }
  }
}
