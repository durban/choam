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

import data.TreiberStack

abstract class AsyncStack[F[_], A] {
  def push: Rxn[A, Unit]
  def pop: F[A]
  def tryPop: Axn[Option[A]]
}

object AsyncStack {

  def apply[F[_], A](implicit F: AsyncReactive[F]): Axn[AsyncStack[F, A]] = {
    TreiberStack[A].flatMapF { es =>
      F.waitList(
        syncGet = es.tryPop,
        syncSet = es.push
      ).map { af =>
        new AsyncStack[F, A] {
          final override def push: A =#> Unit =
            af.set
          final override def pop: F[A] =
            af.asyncGet
          final override def tryPop: Axn[Option[A]] =
            es.tryPop
        }
      }
    }
  }
}
