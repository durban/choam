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

import data.Queue

// TODO: rename to (maybe) WaitList; write docs
final class AsyncFrom[F[_], A] private (
  val syncGet: Axn[Option[A]],
  val syncSet: A =#> Unit,
  waiters: Queue.WithRemove[Promise[F, A]]
) {

  // TODO: Instead of storing promises, could
  // TODO: we store async callbacks directly?
  // TODO: Would it be faster?

  /** Partial, retries if no waiters */
  private[choam] def trySetWaiters: A =#> Unit = {
    this.waiters.tryDeque.flatMap {
      case None => Rxn.unsafe.retry
      case Some(p) => p.complete.void
    }
  }

  def set: A =#> Unit = {
    this.waiters.tryDeque.flatMap {
      case None => this.syncSet
      case Some(p) => p.complete.void
    }
  }

  def get(implicit F: AsyncReactive[F]): F[A] =
    F.monadCancel.bracket(this.getAcq)(this.getUse)(this.getRel)

  private[this] def getAcq(implicit F: AsyncReactive[F]): F[Either[(Promise[F, A], Axn[Unit]), A]] = {
    Promise[F, A].flatMapF { p =>
      this.syncGet.flatMapF {
        case Some(b) => Rxn.pure(Right(b))
        case None => this.waiters.enqueueWithRemover.provide(p).map { remover =>
          Left((p, remover))
        }
      }
    }.run[F]
  }

  private[this] def getRel(r: Either[(Promise[F, A], Axn[Unit]), A])(implicit F: Reactive[F]): F[Unit] = r match {
    case Left((_, remover)) => remover.run[F]
    case Right(_) => F.monad.unit
  }

  private[this] def getUse(r: Either[(Promise[F, A], Axn[Unit]), A])(implicit F: Reactive[F]): F[A] = r match {
    case Left((p, _)) => p.get
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
