/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.~>
import cats.effect.kernel.{ Async, Cont, MonadCancel }

package object async {

  /**
   * This is basically `cats.effect.kernel.Async#asyncCheckAttempt`,
   * which is not released yet.
   *
   * TODO: use that instead of this.
   */
  def asyncCheckAttempt[F[_], A](
    k: (Either[Throwable, A] => Unit) => F[Either[Option[F[Unit]], A]]
  )(implicit F: Async[F]): F[A] = {
    val c = new Cont[F, A, A] {
      final override def apply[G[_]](
        implicit G: MonadCancel[G, Throwable]
      ): (Either[Throwable, A] => Unit, G[A], F ~> G) => G[A] = { (resume, get, lift) =>
        G.uncancelable { poll =>
          G.flatMap(lift(k(resume))) {
            case Left(Some(finalizer)) => G.onCancel(poll(get), lift(finalizer))
            case Left(None) => get
            case Right(result) => G.pure(result)
          }
        }
      }
    }

    F.cont(c)
  }
}
