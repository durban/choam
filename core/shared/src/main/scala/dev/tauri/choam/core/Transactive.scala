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
package core

import cats.effect.kernel.{ Async, Sync, Resource }

import internal.mcas.Mcas

// Note: not really private, published in dev.tauri.choam.stm
private[choam] sealed trait Transactive[F[_]] {
  def commit[B](txn: Txn[F, B]): F[B]
}

// Note: not really private, published in dev.tauri.choam.stm
private[choam] object Transactive {

  @deprecated("Use forAsyncRes", since = "0.4.11") // TODO:0.5: remove
  final def forAsync[F[_]](implicit F: Async[F]): Transactive[F] = {
    new TransactiveImpl(Rxn.DefaultMcas)
  }

  final def forAsyncRes[F[_]](implicit F: Async[F]): Resource[F, Transactive[F]] = // TODO:0.5: rename to `forAsync`
    forAsyncResIn[F, F]

  final def forAsyncResIn[G[_], F[_]](implicit G: Sync[G], F: Async[F]): Resource[G, Transactive[F]] = // TODO:0.5: rename to `forAsyncIn`
    Reactive.defaultMcasResource[G].map(new TransactiveImpl(_))

  /** This allows a `Reactive` and a `Transactive` to share some of their underlying resources */
  final def forReactive[F[_]](implicit F: Async[F], r: Reactive[F]): Resource[F, Transactive[F]] =
    Resource.eval(F.pure(new TransactiveImpl[F](r.mcasImpl)))

  private[choam] final class TransactiveImpl[F[_] : Async](m: Mcas)
    extends Reactive.SyncReactive[F](m) with Transactive[F] {
    final override def commit[B](txn: Txn[F, B]): F[B] = {
      txn.impl.performStm[F, B](null, this.mcasImpl)
    }
  }
}
