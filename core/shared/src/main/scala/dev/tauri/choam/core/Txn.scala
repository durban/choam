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

// Note: not really private, published in dev.tauri.choam.stm
private[choam] sealed trait Txn[F[_], +B] {

  def map[C](f: B => C): Txn[F, C]

  def map2[C, D](that: Txn[F, C])(f: (B, C) => D): Txn[F, D]

  def flatMap[C](f: B => Txn[F, C]): Txn[F, C]

  def orElse[Y >: B](that: Txn[F, Y]): Txn[F, Y]

  private[core] def impl: Axn[B]

  def commit[X >: B](implicit F: Transactive[F]): F[X]
}

// Note: not really private, published in dev.tauri.choam.stm
private[choam] object Txn {

  private[core] trait UnsealedTxn[F[_], +B] extends Txn[F, B]

  final def pure[F[_], A](a: A): Txn[F, A] =
    Rxn.pure(a).castF[F]

  final def unit[F[_]]: Txn[F, Unit] =
    Rxn.unit[Any].castF[F]

  final def retry[F[_], A]: Txn[F, A] =
    Rxn.StmImpl.retryWhenChanged[A].castF[F]

  final def check[F[_]](cond: Boolean): Txn[F, Unit] =
    if (cond) unit else retry

  private[choam] final object unsafe {

    private[choam] final def delay[F[_], A](uf: => A): Txn[F, A] =
      Axn.unsafe.delay[A](uf).castF[F]

    private[choam] final def delayContext[F[_], A](uf: Mcas.ThreadContext => A): Txn[F, A] =
      Rxn.unsafe.delayContext(uf).castF[F]
  }
}
