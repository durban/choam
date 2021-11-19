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
package laws

import cats.effect.kernel.MonadCancel
import cats.laws.IsEq
import cats.laws.IsEqArrow
import cats.syntax.all._

trait ReactiveAsyncLaws[F[_]] extends ReactiveLaws[F] {

  implicit override def reactive: Reactive.Async[F]

  implicit override def monad: MonadCancel[F, _] =
    reactive.monadCancel

  def promiseCompleteAndGet[A](a: A): IsEq[F[(Boolean, A)]] = {
    val completeAndGet = for {
      p <- reactive.run(reactive.promise[A], ())
      ok <- reactive.run(p.complete, a)
      res <- p.get
    } yield (ok, res)
    completeAndGet <-> monad.pure((true, a))
  }
}

object ReactiveAsyncLaws {
  def apply[F[_]](implicit rF: Reactive.Async[F]): ReactiveAsyncLaws[F] = new ReactiveAsyncLaws[F] {
    implicit override def reactive: Reactive.Async[F] = rF
  }
}
