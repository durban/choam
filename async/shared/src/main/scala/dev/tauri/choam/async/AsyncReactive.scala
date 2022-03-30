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
import cats.effect.kernel.{ MonadCancel, Async }

trait AsyncReactive[F[_]] extends Reactive[F] { self =>
  def promise[A]: Axn[Promise[F, A]]
  def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[WaitList[F, A]]
  def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean): Axn[GenWaitList[F, A]]
  def monadCancel: MonadCancel[F, _]
  def mapKAsync[G[_]](t: F ~> G)(implicit G: MonadCancel[G, _]): AsyncReactive[G] = {
    new Reactive.TransformedReactive[F, G](self, t) with AsyncReactive[G] {
      final override def promise[A]: Axn[Promise[G, A]] =
        self.promise[A].map(_.mapK(t))
      final override def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit) =
        self.waitList[A](syncGet, syncSet).map(_.mapK(t)(this))
      override def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean) =
        self.genWaitList(tryGet, trySet).map(_.mapK(t)(this))
      final override def monadCancel: MonadCancel[G, _] =
        G
    }
  }
}

object AsyncReactive {

  def apply[F[_]](implicit inst: AsyncReactive[F]): inst.type =
    inst

  implicit def asyncReactiveForAsync[F[_]](implicit F: Async[F]): AsyncReactive[F] =
    new AsyncReactiveImpl[F](mcas.Mcas.DefaultMcas)(F)

  private[async] class AsyncReactiveImpl[F[_]](mi: mcas.Mcas)(implicit F: Async[F])
    extends Reactive.SyncReactive[F](mi)
    with AsyncReactive[F] {

    final override def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean) =
      GenWaitList.genWaitListForAsync[F, A](tryGet, trySet)(F, this)

    final override def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[WaitList[F, A]] =
      GenWaitList.waitListForAsync(syncGet, syncSet)(F, this)

    final override def promise[A]: Axn[Promise[F, A]] =
      Promise.forAsync[F, A](this, F)

    final override def monadCancel =
      F
  }
}
