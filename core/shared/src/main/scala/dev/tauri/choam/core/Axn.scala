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

import internal.mcas.Mcas

/**
 * Pseudo-companion object for the type alias [[Axn]].
 */
object Axn {

  final def pure[A](a: A): Axn[A] =
    Rxn.pure(a)

  final def unit: Axn[Unit] =
    pure(())

  final def panic[A](ex: Throwable): Axn[A] =
    Rxn.panic(ex)

  private[choam] final object unsafe {

    private[choam] final def delay[A](da: => A): Axn[A] =
      delayImpl(da)

    private[core] final def delayImpl[A](da: => A): RxnImpl[Any, A] =
      Rxn.unsafe.delayImpl[Any, A](_ => da)

    private[choam] final def suspend[A](daa: => Axn[A]): Axn[A] =
      suspendImpl(daa)

    private[core] final def suspendImpl[A](daa: => Axn[A]): RxnImpl[Any, A] = // TODO: optimize
      this.delayImpl(daa).flatten

    private[choam] final def delayContext[A](uf: Mcas.ThreadContext => A): Axn[A] =
      Rxn.unsafe.delayContext(uf)

    private[choam] final def suspendContext[A](uf: Mcas.ThreadContext => Axn[A]): Axn[A] =
      Rxn.unsafe.suspendContext(uf)
  }
}
