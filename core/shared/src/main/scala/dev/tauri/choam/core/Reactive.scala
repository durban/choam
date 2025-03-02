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

import cats.{ ~>, Monad }
import cats.effect.kernel.{ Sync, Resource }

import internal.mcas.Mcas

// TODO: We should have a way to "propagate"
// TODO: a `Strategy`, because this way a
// TODO: data structure might just run things
// TODO: with the `Default` (e.g., CDL#toCats).

sealed trait Reactive[F[_]] extends ~>[Axn, F] { self =>
  def apply[A, B](r: Rxn[A, B], a: A, s: RetryStrategy.Spin = RetryStrategy.Default): F[B]
  private[choam] def mcasImpl: Mcas
  def monad: Monad[F]
  final def run[A](a: Axn[A], s: RetryStrategy.Spin = RetryStrategy.Default): F[A] =
    this.apply[Any, A](a, null: Any, s)
  final override def apply[A](a: Axn[A]): F[A] =
    this.run(a, RetryStrategy.Default)
}

object Reactive {

  private[choam] trait UnsealedReactive[F[_]] extends Reactive[F]

  final def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  final def from[F[_]](rt: RxnRuntime)(implicit F: Sync[F]): Resource[F, Reactive[F]] =
    fromIn[F, F](rt)

  final def fromIn[G[_], F[_]](rt: RxnRuntime)(implicit @unused G: Sync[G], F: Sync[F]): Resource[G, Reactive[F]] =
    Resource.pure(new SyncReactive(rt.mcasImpl))

  final def forSync[F[_]](implicit F: Sync[F]): Resource[F, Reactive[F]] =
    forSyncIn[F, F]

  final def forSyncIn[G[_], F[_]](implicit G: Sync[G], F: Sync[F]): Resource[G, Reactive[F]] =
    RxnRuntime[G].flatMap(rt => fromIn(rt))

  private[choam] class SyncReactive[F[_]](
    final override val mcasImpl: Mcas,
  )(implicit F: Sync[F]) extends Reactive[F] {

    final override def apply[A, B](r: Rxn[A, B], a: A, s: RetryStrategy.Spin): F[B] = {
      F.delay { r.unsafePerform(a = a, mcas = this.mcasImpl, strategy = s) }
    }

    final override def monad: Monad[F] =
      F
  }

  private[choam] class TransformedReactive[F[_], G[_]](
    underlying: Reactive[F],
    t: F ~> G,
  )(implicit G: Monad[G]) extends Reactive[G] {
    final override def apply[A, B](r: Rxn[A, B], a: A, s: RetryStrategy.Spin): G[B] =
      t(underlying.apply(r, a, s))
    final override def mcasImpl: Mcas =
      underlying.mcasImpl
    final override def monad: Monad[G] =
      G
  }
}
