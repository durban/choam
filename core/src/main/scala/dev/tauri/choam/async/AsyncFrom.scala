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

final class AsyncFrom[F[_], A, B] private (
  syncGet: A =#> Option[B],
  syncSet: B =#> Unit,
  waiters: Queue.WithRemove[Promise[F, B]]
) {

  def set: B =#> Unit = {
    this.waiters.tryDeque.flatMap {
      case None => this.syncSet
      case Some(p) => p.complete.discard
    }
  }

  def get(a: A)(implicit F: Reactive.Async[F]): F[B] = {
    val acq = Promise[F, B].flatMap { p =>
      this.syncGet.provide(a).flatMap {
        case Some(b) => Axn.pure(Right(b))
        case None => this.waiters.enqueue.provide(p).as(Left(p))
      }
    }.run[F]
    val rel: (Either[Promise[F, B], B] => F[Unit]) = {
      case Left(p) => this.waiters.remove.discard[F](p)
      case Right(_) => F.monadCancel.unit
    }
    F.monadCancel.bracket(acquire = acq) {
      case Left(p) => p.get
      case Right(a) => F.monadCancel.pure(a)
    } (release = rel)
  }
}

object AsyncFrom {

  def apply[F[_], A, B](syncGet: A =#> Option[B], syncSet: B =#> Unit): Axn[AsyncFrom[F, A, B]] = {
    Queue.withRemove[Promise[F, B]].map { waiters =>
      new AsyncFrom[F, A, B](syncGet, syncSet, waiters)
    }
  }
}
