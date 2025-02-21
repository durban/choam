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

  final def commit[B](txn: Txn[F, B]): F[B] =
    this.commit(txn, RetryStrategy.DefaultSleep)

  private[choam] def commit[B](txn: Txn[F, B], str: RetryStrategy): F[B]

  private[choam] def commitWithStepper[B](txn: Txn[F, B], stepper: RetryStrategy.Internal.Stepper[F]): F[B]
}

// Note: not really private, published in dev.tauri.choam.stm
private[choam] object Transactive {

  final def apply[F[_]](implicit F: Transactive[F]): F.type =
    F

  final def from[F[_]](rt: RxnRuntime)(implicit F: Async[F]): Resource[F, Transactive[F]] =
    fromIn[F, F](rt)

  final def fromIn[G[_], F[_]](rt: RxnRuntime)(implicit @unused G: Sync[G], F: Async[F]): Resource[G, Transactive[F]] =
    Resource.pure(new TransactiveImpl(rt.mcasImpl))

  final def forAsync[F[_]](implicit F: Async[F]): Resource[F, Transactive[F]] =
    forAsyncIn[F, F]

  final def forAsyncIn[G[_], F[_]](implicit G: Sync[G], F: Async[F]): Resource[G, Transactive[F]] =
    RxnRuntime[G].flatMap(rt => fromIn(rt))

  private[choam] final class TransactiveImpl[F[_] : Async](m: Mcas)
    extends Reactive.SyncReactive[F](m) with Transactive[F] {
    final override def commit[B](txn: Txn[F, B], str: RetryStrategy): F[B] = {
      txn.impl.performStm[F, B](null, this.mcasImpl, str)
    }
    private[choam] final override def commitWithStepper[B](txn: Txn[F, B], stepper: RetryStrategy.Internal.Stepper[F]): F[B] = {
      txn.impl.performStmWithStepper(null, this.mcasImpl, stepper)
    }
  }
}
