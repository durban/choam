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
  waiters: MichaelScottQueue[Promise[F, A]]
) extends AsyncStack[F, A] {

  override val push: Reaction[A, Unit] = {
    this.waiters.tryDeque.flatMapU {
      case None => this.elements.push
      case Some(p) => p.complete.discard
    }
  }

  override def pop(implicit F: Reactive.Async[F]): F[A] = {
    val r: Action[Either[Promise[F, A], A]] = Promise[F, A].flatMapU { p =>
      this.elements.tryPop.flatMapU {
        case Some(a) => Action.ret(Right(a))
        case None => this.waiters.enqueue.lmap[Any] { _ => p }.map { _ => Left(p) }
      }
    }

    F.monadCancel.flatMap(r.run[F]) {
      case Left(p) => p.get // TODO: if this `get` is cancelled, we're in trouble
      case Right(a) => F.monadCancel.pure(a)
    }
  }
}

private[choam] object AsyncStack2 {

  def apply[F[_], A]: Action[AsyncStack[F, A]] = {
    TreiberStack[A].flatMap { es =>
      MichaelScottQueue[Promise[F, A]].map { ws =>
        new AsyncStack2[F, A](es, ws)
      }
    }
  }
}
