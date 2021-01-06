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

import cats.effect.kernel.Sync

trait Reactive[F[_]] {
  def run[A, B](r: React[A, B], a: A): F[B]
  def kcasImpl: kcas.KCAS
}

final object Reactive {

  def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  def defaultKcasImpl: kcas.KCAS =
    kcas.KCAS.EMCAS

  implicit def reactiveForSync[F[_]](implicit F: Sync[F]): Reactive[F] =
    new SyncReactive[F](defaultKcasImpl)(F)

  private[choam] final class SyncReactive[F[_]](
    final override val kcasImpl: kcas.KCAS
  )(implicit F: Sync[F]) extends Reactive[F] {
      final override def run[A, B](r: React[A, B], a: A): F[B] =
        F.delay { r.unsafePerform(a, this.kcasImpl) }
    }
}
