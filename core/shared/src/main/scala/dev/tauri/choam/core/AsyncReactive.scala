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

sealed trait AsyncReactive[F[_]] extends Reactive.UnsealedReactive[F] { self =>
  def applyAsync[A, B](r: Rxn[B], s: RetryStrategy = RetryStrategy.Default): F[B] // TODO:0.5: rename to `runAsync`
  private[choam] def asyncInst: Async[F]
}

object AsyncReactive {

  final def apply[F[_]](implicit inst: AsyncReactive[F]): inst.type =
    inst

  final def from[F[_]](rt: ChoamRuntime)(implicit F: Async[F]): Resource[F, AsyncReactive[F]] =
    fromIn[F, F](rt)

  final def fromIn[G[_], F[_]](rt: ChoamRuntime)(implicit @unused G: Sync[G], F: Async[F]): Resource[G, AsyncReactive[F]] =
    Resource.pure(new AsyncReactiveImpl(rt.mcasImpl))

  private[choam] class AsyncReactiveImpl[F[_]](mi: Mcas)(implicit F: Async[F])
    extends Reactive.SyncReactive[F](mi)
    with AsyncReactive[F] {

    final override def applyAsync[A, B](r: Rxn[B], s: RetryStrategy = RetryStrategy.Default): F[B] =
      r.performInternal[F, B](this.mcasImpl, s)(using F)

    private[choam] final override def asyncInst =
      F
  }
}
