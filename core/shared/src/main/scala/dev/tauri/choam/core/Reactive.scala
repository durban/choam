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
package core

import cats.{ ~>, Monad }
import cats.effect.kernel.Sync

import mcas.Mcas

trait Reactive[F[_]] { self =>
  def apply[A, B](r: Rxn[A, B], a: A): F[B]
  def mcasImpl: Mcas
  def monad: Monad[F]
  def mapK[G[_]](t: F ~> G)(implicit G: Monad[G]): Reactive[G] =
    new Reactive.TransformedReactive[F, G](self, t)
  def run[A](a: Axn[A]): F[A] =
    this.apply[Any, A](a, null: Any)
  def applyInterruptibly[A, B](r: Rxn[A, B], a: A): F[B] =
    this.apply(r, a) // default implementation, interruptible `F` should override
}

object Reactive {

  def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  implicit def reactiveForSync[F[_]](implicit F: Sync[F]): Reactive[F] =
    new SyncReactive[F](Mcas.DefaultMcas)(F)

  private[choam] class SyncReactive[F[_]](
    final override val mcasImpl: Mcas
  )(implicit F: Sync[F]) extends Reactive[F] {

    final override def apply[A, B](r: Rxn[A, B], a: A): F[B] = {
      F.delay {
        r.unsafePerform(a, this.mcasImpl)
      }
    }

    final override def applyInterruptibly[A, B](r: Rxn[A, B], a: A): F[B] = {
      F.interruptible {
        r.unsafePerform(a, this.mcasImpl)
      }
    }

    final override def monad =
      F
  }

  private[choam] class TransformedReactive[F[_], G[_]](
    underlying: Reactive[F],
    t: F ~> G,
  )(implicit G: Monad[G]) extends Reactive[G] {
    final override def apply[A, B](r: Rxn[A, B], a: A): G[B] =
      t(underlying.apply(r, a))
    final override def mcasImpl: Mcas =
      underlying.mcasImpl
    final override def monad: Monad[G] =
      G
  }
}