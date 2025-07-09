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

  // Note: don't put implicits here, because it is not a real companion object!

  final def pure[A](a: A): Axn[A] =
    Rxn.pure(a)

  final def unit: Axn[Unit] =
    pure(())

  private[this] final val _none: Axn[Option[Nothing]] =
    pure(None)

  private[choam] final def none[A]: Axn[Option[A]] =
    _none

  final object unsafe {

    @inline
    final def delay[A](da: => A): Axn[A] =
      delayImpl(da)

    private[choam] final def delayImpl[A](da: => A): RxnImpl[A] = ???

    @inline
    private[choam] final def suspend[A](daa: => Axn[A]): Axn[A] =
      suspendImpl(daa)

    private[choam] final def suspendImpl[A](daa: => Axn[A]): RxnImpl[A] =
      this.delayImpl(daa).flatten

    @inline
    private[choam] final def delayContext[A](uf: Mcas.ThreadContext => A): Axn[A] =
      Rxn.unsafe.delayContextImpl(uf)

    @inline
    private[choam] final def suspendContext[A](uf: Mcas.ThreadContext => Axn[A]): Axn[A] =
      this.delayContext(uf).flatten

    final def panic[A](ex: Throwable): Axn[A] =
      Rxn.unsafe.panic(ex)
  }
}
