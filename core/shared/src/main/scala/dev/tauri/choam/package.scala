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

package dev.tauri

package object choam {

  import internal.mcas.Mcas

  private[choam] type tailrec = scala.annotation.tailrec

  private[choam] type switch = scala.annotation.switch

  private[choam] type unused = scala.annotation.unused

  private[choam] type nowarn = scala.annotation.nowarn

  final type Rxn[-A, +B] = core.Rxn[A, B]

  final type =#>[-A, +B] = core.Rxn[A, B]

  final val Rxn: core.Rxn.type = core.Rxn

  final type Reactive[F[_]] = core.Reactive[F]

  final val Reactive: core.Reactive.type = core.Reactive

  final type Ref[A] = refs.Ref[A]

  final val Ref: refs.Ref.type = refs.Ref

  final type RefLike[A] = refs.RefLike[A]

  final val RefLike: refs.RefLike.type = refs.RefLike

  /*
   * Implementation note: in some cases, composing
   * `Rxn`s with `>>>` (or `*>`) will be faster
   * than using `flatMap`. An example (with measurements)
   * is in `ArrowBench`.
   *
   * TODO: More benchmarks needed to determine exactly
   * TODO: what it is that makes them faster. Also,
   * TODO: maybe we could optimize `flatMap`.
   */

  /**
   * The description of an effect, which (when executed),
   * results in a value of type `A`. During execution,
   * it may update any number of [[dev.tauri.choam.Ref Ref]]s atomically. (It
   * may also create new `Ref`s.)
   *
   * This type forms a `Monad`. However, when composing
   * these kinds of effects, also consider using [[dev.tauri.choam.core.Rxn Rxn]]
   * and `>>>` or `*>` instead of `flatMap`.
   *
   * The relation between [[Axn]] and [[dev.tauri.choam.core.Rxn Rxn]] is approximately
   * `Axn[A] ≡ Axn[Any, A]`; or, alternatively
   * `Rxn[A, B] ≡ (A => Axn[B])` (see [[dev.tauri.choam.core.Rxn!.toFunction toFunction]]).
   */
  final type Axn[+A] = Rxn[Any, A] // short for 'astaxanthin'

  /**
   * Pseudo-companion object for the type alias `Axn`.
   */
  final object Axn {

    final object unsafe {
      def delay[A](da: => A): Axn[A] =
        Rxn.unsafe.delay[Any, A](_ => da)
      def suspend[A](daa: => Axn[A]): Axn[A] = // TODO: optimize
        this.delay(daa).flatten
      def context[A](uf: Mcas.ThreadContext => A): Axn[A] =
        Rxn.unsafe.context(uf)
      def suspendContext[A](uf: Mcas.ThreadContext => Axn[A]): Axn[A] =
        Rxn.unsafe.suspendContext(uf)
    }
  }

  // Note: using these always leaves a check for
  // the package object in the bytecode (getstatic
  // and a null check). However, microbenchmarks
  // couldn't show a difference between these methods
  // and Java static method equivalents (see StaticsBench).

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

  private[choam] final def requireNonNull[A](a: A): Unit =
    require(!isNull(a))
}
