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

import cats.effect.kernel.Async

import data.Queue

import AsyncFrom.Callback

private final class AsyncFrom[F[_], A] private (
  val syncGet: Axn[Option[A]],
  val syncSet: A =#> Unit,
  waiters: Queue.WithRemove[Callback[A]],
)(implicit F: Async[F], arF: AsyncReactive[F]) {

  /** Partial, retries if no waiters */
  private[choam] def trySetWaiters: A =#> Unit = {
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

private object AsyncFrom {

  type Callback[A] =
    Either[Throwable, A] => Unit

  def apply[F[_] : Async : AsyncReactive, A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[AsyncFrom[F, A]] = {
    Queue.withRemove[Callback[A]].map { waiters =>
      new AsyncFrom[F, A](syncGet, syncSet, waiters)
    }
  }
}
