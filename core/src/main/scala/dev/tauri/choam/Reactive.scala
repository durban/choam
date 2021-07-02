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

import cats.Monad
import cats.effect.kernel.{ Sync, MonadCancel }
import cats.effect.{ kernel => ce }

import async.Promise

trait Reactive[F[_]] {
  def run[A, B](r: Rxn[A, B], a: A): F[B]
  def kcasImpl: kcas.KCAS
  def monad: Monad[F]
}

object Reactive {

  def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  def defaultKcasImpl: kcas.KCAS =
    kcas.KCAS.EMCAS

  implicit def reactiveForSync[F[_]](implicit F: Sync[F]): Reactive[F] =
    new SyncReactive[F](defaultKcasImpl)(F)

  private[choam] class SyncReactive[F[_]](
    final override val kcasImpl: kcas.KCAS
  )(implicit F: Sync[F]) extends Reactive[F] {
    final override def run[A, B](r: Rxn[A, B], a: A): F[B] = {
      F.delay {
        r.unsafePerform(a, this.kcasImpl)
        // Rxn.externalInterpreter(r, a, this.kcasImpl.currentContext())
      }
    }
    final override def monad =
      F
  }

  trait Async[F[_]] extends Reactive[F] {
    def promise[A]: Axn[async.Promise[F, A]]
    def monadCancel: MonadCancel[F, _]
  }

  final object Async {

    def apply[F[_]](implicit inst: Reactive.Async[F]): inst.type =
      inst

    implicit def reactiveAsyncForAsync[F[_]](implicit F: ce.Async[F]): Reactive.Async[F] =
      new AsyncReactive[F](defaultKcasImpl)(F)
  }

  private[choam] class AsyncReactive[F[_]](ki: kcas.KCAS)(implicit F: ce.Async[F])
    extends SyncReactive[F](ki)
    with Reactive.Async[F] {

    final override def promise[A]: Axn[Promise[F, A]] =
      async.Promise.forAsync[F, A](this, F)

    final override def monadCancel =
      F
  }
}
