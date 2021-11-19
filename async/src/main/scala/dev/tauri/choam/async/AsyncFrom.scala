/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

final class AsyncFrom[F[_], A] private (
  syncGet: Axn[Option[A]],
  syncSet: A =#> Unit,
  waiters: Queue.WithRemove[Promise[F, A]]
) {

  def set: A =#> Unit = {
    this.waiters.tryDeque.flatMap {
      case None => this.syncSet
      case Some(p) => p.complete.void
    }
  }

  def get(implicit F: AsyncReactive[F]): F[A] =
    F.monadCancel.bracket(this.getAcq)(this.getUse)(this.getRel)

  def getResource(implicit F: AsyncReactive[F]): Resource[F, F[A]] =
    Resource.make(this.getAcq)(this.getRel)(F.monad).map(this.getUse)

  private[this] def getAcq(implicit F: AsyncReactive[F]): F[Either[Promise[F, A], A]] = {
    Promise[F, A].flatMap { p =>
      this.syncGet.flatMap {
        case Some(b) => Rxn.pure(Right(b))
        case None => this.waiters.enqueue.provide(p).as(Left(p))
      }
    }.run[F]
  }

  private[this] def getRel(r: Either[Promise[F, A], A])(implicit F: Reactive[F]): F[Unit] = r match {
    case Left(p) => this.waiters.remove.void[F](p)
    case Right(_) => F.monad.unit
  }

  private[this] def getUse(r: Either[Promise[F, A], A])(implicit F: Reactive[F]): F[A] = r match {
    case Left(p) => p.get
    case Right(a) => F.monad.pure(a)
  }
}

object AsyncFrom {

  def apply[F[_], A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[AsyncFrom[F, A]] = {
    Queue.withRemove[Promise[F, A]].map { waiters =>
      new AsyncFrom[F, A](syncGet, syncSet, waiters)
    }
  }
}
