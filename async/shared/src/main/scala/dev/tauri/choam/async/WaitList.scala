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

import cats.~>
import cats.effect.kernel.Async

abstract class WaitList[F[_], A] { self =>

  def set: A =#> Unit

  def get: F[A]

  def syncGet: Axn[Option[A]] // TODO: better name

  def unsafeSetWaitersOrRetry: A =#> Unit // TODO: better name (or remove)

  def mapK[G[_]](t: F ~> G): WaitList[G, A] = {
    new WaitList[G, A] {
      override def set =
        self.set
      override def get =
        t(self.get)
      override def syncGet =
        self.syncGet
      override def unsafeSetWaitersOrRetry =
        self.unsafeSetWaitersOrRetry
    }
  }
}

object WaitList {

  private type Callback[A] =
    Either[Throwable, A] => Unit

  def forAsync[F[_], A](
    _syncGet: Axn[Option[A]],
    _syncSet: A =#> Unit
  )(implicit F: Async[F], rF: AsyncReactive[F]): Axn[WaitList[F, A]] = {
    data.Queue.withRemove[Callback[A]].map { waiters =>
      new AsyncWaitList[F, A](_syncGet, _syncSet, waiters)
    }
  }

  private final class AsyncWaitList[F[_], A](
    val syncGet: Axn[Option[A]],
    val syncSet: A =#> Unit,
    waiters: data.Queue.WithRemove[Callback[A]],
  )(implicit F: Async[F], arF: AsyncReactive[F]) extends WaitList[F, A] {

    /** Partial, retries if no waiters */
    final def unsafeSetWaitersOrRetry: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None =>
          Rxn.unsafe.retry
        case Some(cb) =>
          callCb(cb)
      }
    }

    private[this] final def callCb(cb: Callback[A]): Rxn[A, Unit] = {
      Rxn.identity[A].postCommit(Rxn.unsafe.delay { (a: A) =>
        cb(Right(a))
      }).void
    }

    def set: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None =>
          this.syncSet
        case Some(cb) =>
          callCb(cb)
      }
    }

    def get: F[A] = {
      F.async[A] { cb =>
        val rxn: Axn[Either[Axn[Unit], A]] = this.syncGet.flatMapF {
          case Some(a) =>
            Rxn.pure(Right(a))
          case None =>
            this.waiters.enqueueWithRemover.provide(cb).map { remover =>
              Left(remover)
            }
        }
        F.flatMap[Either[Axn[Unit], A], Option[F[Unit]]](arF.run(rxn)) {
          case Right(a) =>
            F.as(F.delay(cb(Right(a))), None)
          case Left(remover) =>
            F.pure(Some(arF.run(remover)))
        }
      }
    }
  }
}
