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

package dev.tauri

package object choam extends internal.ChoamUtils {

  private[choam] type tailrec = scala.annotation.tailrec

  private[choam] type switch = scala.annotation.switch

  private[choam] type unused = scala.annotation.unused

  private[choam] type nowarn = scala.annotation.nowarn

  final type Rxn[-A, +B] = core.Rxn[A, B]

  final type =#>[-A, +B] = core.Rxn[A, B]

  final val Rxn: core.Rxn.type = core.Rxn

  final type Axn[+A] = core.Axn[A]

  final val Axn: core.Axn.type = core.Axn

  final type Reactive[F[_]] = core.Reactive[F]

  final val Reactive: core.Reactive.type = core.Reactive

  final type Ref[A] = refs.Ref[A]

  final val Ref: refs.Ref.type = refs.Ref

  final type RefLike[A] = refs.RefLike[A]

  final val RefLike: refs.RefLike.type = refs.RefLike

  // Note: using these always leaves a check for
  // the package object in the bytecode (getstatic
  // and a null check). However, microbenchmarks
  // couldn't show a difference between these methods
  // and Java static method equivalents (see StaticsBench).

  @inline
  private[choam] final def box[A](a: A): AnyRef =
    internal.mcas.box[A](a)

  @inline
  private[choam] final def equ[A](x: A, y: A): Boolean =
    internal.mcas.equ(x, y)

  @inline
  private[choam] final def isNull[A](a: A): Boolean =
    internal.mcas.isNull(a)

  @inline
  private[choam] final def nullOf[A]: A =
    internal.mcas.nullOf[A]

  private[choam] final def impossible(s: String): Nothing =
    internal.mcas.impossible(s)

  private[choam] implicit final class AxnSyntax2[A](private val self: Axn[A]) {
    @nowarn("cat=deprecation")
    private[choam] final def unsafeRun(mcas: internal.mcas.Mcas): A = {
      self.unsafePerform(null : Any, mcas)
    }
  }
}
