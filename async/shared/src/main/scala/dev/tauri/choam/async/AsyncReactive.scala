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

import cats.{ ~>, Monad }
import cats.effect.kernel.{ Async, Resource }

import internal.mcas.Mcas
import core.RetryStrategy

trait AsyncReactive[F[_]] extends Reactive[F] { self => // TODO:0.5: make it sealed
  def applyAsync[A, B](r: Rxn[A, B], a: A, s: RetryStrategy = RetryStrategy.Default): F[B]
  def promise[A]: Axn[Promise[F, A]]
  def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[WaitList[F, A]]
  def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean): Axn[GenWaitList[F, A]]
  final override def mapK[G[_]](t: F ~> G)(implicit G: Monad[G]): AsyncReactive[G] = {
    new Reactive.TransformedReactive[F, G](self, t) with AsyncReactive[G] {
      final override def applyAsync[A, B](r: Rxn[A, B], a: A, s: RetryStrategy = RetryStrategy.Default): G[B] =
        t(self.applyAsync(r, a, s))
      final override def promise[A]: Axn[Promise[G, A]] =
        self.promise[A].map(_.mapK(t))
      final override def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit) =
        self.waitList[A](syncGet, syncSet).map(_.mapK(t)(this))
      final override def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean) =
        self.genWaitList(tryGet, trySet).map(_.mapK(t)(this))
    }
  }
}

object AsyncReactive {

  def apply[F[_]](implicit inst: AsyncReactive[F]): inst.type =
    inst

  @deprecated("Use forAsyncRes", since = "0.4.11") // TODO:0.5: remove
  def forAsync[F[_]](implicit F: Async[F]): AsyncReactive[F] =
    new AsyncReactiveImpl[F](Rxn.DefaultMcas)(F)

  final def forAsyncRes[F[_]](implicit F: Async[F]): Resource[F, AsyncReactive[F]] = // TODO:0.5: rename to forAsync
    Reactive.defaultMcasResource[F].map(new AsyncReactiveImpl(_))

  private[choam] class AsyncReactiveImpl[F[_]](mi: Mcas)(implicit F: Async[F])
    extends Reactive.SyncReactive[F](mi)
    with AsyncReactive[F] {

    final override def applyAsync[A, B](r: Rxn[A, B], a: A, s: RetryStrategy = RetryStrategy.Default): F[B] =
      r.perform[F, B](a, this.mcasImpl, s)(F)

    final override def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean) =
      GenWaitList.genWaitListForAsync[F, A](tryGet, trySet)(F, this)

    final override def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[WaitList[F, A]] =
      GenWaitList.waitListForAsync(syncGet, syncSet)(F, this)

    final override def promise[A]: Axn[Promise[F, A]] =
      Promise.forAsync[F, A](this, F)
  }
}
