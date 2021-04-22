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

package dev.tauri

package object choam {

  private[choam] type tailrec = scala.annotation.tailrec

  private[choam] type unused = scala.annotation.unused

  private[choam] type nowarn = scala.annotation.nowarn

  final type Rxn[-A, +B] = React[A, B] // short for 'reaction'

  final val Rxn: React.type = React

  final type =#>[-A, +B] = Rxn[A, B]

  /*
   * Implementation note: in some cases, composing
   * `Reaction`s with `>>>` (or `*>`) will be faster
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
   * it may update any number of [[Ref]]s atomically. (It
   * may also create new `Ref`s.)
   *
   * This type forms a `Monad`. However, when composing
   * these kinds of effects, also consider using [[Rxn]]
   * and `>>>` or `*>` instead of `flatMap`.
   *
   * The relation between [[Axn]] and [[Rxn]] is approximately
   * `Axn[A] ≡ Axn[Any, A]`; or, alternatively
   * `Rxn[A, B] ≡ (A => Axn[B])` (see [[React#toFunction]]).
   */
  final type Axn[+A] = React[Any, A] // short for 'astaxanthin'

  final val Axn: React.type = React

  // Note: using these always leaves a check for
  // the package object in the bytecode (getstatic
  // and a null check). However, microbenchmarks
  // couldn't show a difference between these methods
  // and Java static method equivalents (see StaticsBench).

  @inline
  private[this] def box[A](a: A): AnyRef =
    a.asInstanceOf[AnyRef]

  @inline
  private[choam] def equ[A](x: A, y: A): Boolean =
    box(x) eq box(y)

  @inline
  private[choam] def isNull[A](a: A): Boolean =
    box(a) eq null

  @inline
  private[choam] def nullOf[A]: A =
    null.asInstanceOf[A]

  // TODO: maybe AssertionError?
  private[choam] def impossible(s: String): Nothing =
    throw new IllegalStateException(s)
}
