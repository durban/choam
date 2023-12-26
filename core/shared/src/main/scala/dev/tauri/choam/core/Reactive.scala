/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
import cats.effect.kernel.Sync

import internal.mcas.Mcas

// TODO: Add a way to run with `interpretAsync` (in AsyncReactive)
trait Reactive[F[_]] extends ~>[Axn, F] { self =>
  def apply[A, B](r: Rxn[A, B], a: A, s: Rxn.Strategy.LockFree = Rxn.Strategy.Default): F[B]
  def mcasImpl: Mcas
  def monad: Monad[F]
  final def mapK[G[_]](t: F ~> G)(implicit G: Monad[G]): Reactive[G] =
    new Reactive.TransformedReactive[F, G](self, t)
  final def run[A](a: Axn[A], s: Rxn.Strategy.LockFree = Rxn.Strategy.Default): F[A] =
    this.apply[Any, A](a, null: Any, s)
  final override def apply[A](a: Axn[A]): F[A] =
    this.run(a, Rxn.Strategy.Default)
}

object Reactive {

  def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  def forSync[F[_]](implicit F: Sync[F]): Reactive[F] =
    new SyncReactive[F](Rxn.DefaultMcas)(F)

  private[choam] class SyncReactive[F[_]](
    final override val mcasImpl: Mcas
  )(implicit F: Sync[F]) extends Reactive[F] {

    final override def apply[A, B](r: Rxn[A, B], a: A, s: Rxn.Strategy.LockFree): F[B] = {
      F.delay { r.unsafePerform(a = a, mcas = this.mcasImpl, strategy = s) }
    }

    final override def monad: Monad[F] =
      F
  }

  private[choam] class TransformedReactive[F[_], G[_]](
    underlying: Reactive[F],
    t: F ~> G,
  )(implicit G: Monad[G]) extends Reactive[G] {
    final override def apply[A, B](r: Rxn[A, B], a: A, s: Rxn.Strategy.LockFree): G[B] =
      t(underlying.apply(r, a, s))
    final override def mcasImpl: Mcas =
      underlying.mcasImpl
    final override def monad: Monad[G] =
      G
  }
}
