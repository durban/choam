/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.{ Monad, ~> }
import cats.effect.kernel.Sync

trait Reactive[F[_]] { self =>
  // TODO: rename `run` to `apply`, and make `def run[A](a: Axn[A]): F[A]`
  def run[A, B](r: Rxn[A, B], a: A): F[B]
  def kcasImpl: mcas.KCAS
  def monad: Monad[F]
  def mapK[G[_]](t: F ~> G)(implicit G: Monad[G]): Reactive[G] =
    new Reactive.TransformedReactive[F, G](self, t)
}

object Reactive {

  def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  def defaultKcasImpl: mcas.KCAS =
    mcas.KCAS.EMCAS

  implicit def reactiveForSync[F[_]](implicit F: Sync[F]): Reactive[F] =
    new SyncReactive[F](defaultKcasImpl)(F)

  private[choam] class SyncReactive[F[_]](
    final override val kcasImpl: mcas.KCAS
  )(implicit F: Sync[F]) extends Reactive[F] {
    final override def run[A, B](r: Rxn[A, B], a: A): F[B] = {
      F.delay {
        r.unsafePerform(a, this.kcasImpl)
      }
    }
    final override def monad =
      F
  }

  private[choam] class TransformedReactive[F[_], G[_]](
    underlying: Reactive[F],
    t: F ~> G,
  )(implicit G: Monad[G]) extends Reactive[G] {
    final override def run[A, B](r: Rxn[A, B], a: A): G[B] =
      t(underlying.run(r, a))
    final override def kcasImpl: mcas.KCAS =
      underlying.kcasImpl
    final override def monad: Monad[G] =
      G
  }
}
