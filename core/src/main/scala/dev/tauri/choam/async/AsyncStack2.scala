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

private[choam] final class AsyncStack2[F[_], A] private (
  elements: TreiberStack[A],
  waiters: Queue.WithRemove[Promise[F, A]]
) extends AsyncStack[F, A] {

  override val push: Rxn[A, Unit] = {
    this.waiters.tryDeque.flatMap {
      case None => this.elements.push
      case Some(p) => p.complete.void
    }
  }

  override def pop(implicit F: Reactive.Async[F]): F[A] = {
    F.monadCancel.flatMap(F.promise[A].run[F]) { p =>
      val acq = this.elements.tryPop.flatMap {
        case Some(a) => Rxn.ret(Right(a))
        case None => this.waiters.enqueue.provide(p).as(Left(p))
      }.run[F]
      val rel: (Either[Promise[F, A], A] => F[Unit]) = {
        case Left(p) => this.waiters.remove.void[F](p)
        case Right(_) => F.monadCancel.unit
      }
      F.monadCancel.bracket(acquire = acq)(use = {
        case Left(p) => p.get
        case Right(a) => F.monadCancel.pure(a)
      })(release = rel)
    }
  }
}

private[choam] object AsyncStack2 {

  def apply[F[_], A]: Axn[AsyncStack[F, A]] = {
    TreiberStack[A].flatMap { es =>
      Queue.withRemove[Promise[F, A]].map { ws =>
        new AsyncStack2[F, A](es, ws)
      }
    }
  }
}
