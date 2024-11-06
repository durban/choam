/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package core

import internal.mcas.Mcas

private[choam] trait Txn[F[_], +B] { // TODO: sealed

  def map[C](f: B => C): Txn[F, C]

  def flatMap[C](f: B => Txn[F, C]): Txn[F, C]

  private[core] def impl: Axn[B]

  final def commit[X >: B](implicit F: Transactive[F]): F[X] =
    F.commit(this)
}

private[choam] object Txn {

  final def pure[F[_], A](a: A): Txn[F, A] =
    Rxn.pure(a).castF[F]

  final def retry[F[_], A]: Txn[F, A] =
    Rxn.unsafe.retry[Any, A].castF[F] // TODO: retry when changed

  private[choam] final object unsafe {
    private[choam] final def delayContext[F[_], A](uf: Mcas.ThreadContext => A): Txn[F, A] =
      Rxn.unsafe.delayContext(uf).castF[F]
  }
}
