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

sealed abstract class WaitList[F[_], A] { self =>

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

  def forAsync[F[_], A](
    _syncGet: Axn[Option[A]],
    _syncSet: A =#> Unit
  )(implicit F: Async[F], rF: AsyncReactive[F]): Axn[WaitList[F, A]] = {
    // TODO: remove indirection (AsyncFrom extend WaitList)
    AsyncFrom.apply[F, A](_syncGet, _syncSet).map { af =>
      new WaitList[F, A] {
        final def set: A =#> Unit =
          af.set
        final def get: F[A] =
          af.get
        final def syncGet =
          _syncGet
        final def unsafeSetWaitersOrRetry: A =#> Unit =
          af.trySetWaiters
      }
    }
  }
}
