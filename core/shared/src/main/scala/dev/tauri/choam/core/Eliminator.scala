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

sealed trait Eliminator[-A, +B, -C, +D] {
  def leftOp(a: A): Rxn[B]
  def rightOp(c: C): Rxn[D]
}

object Eliminator {

  private[core] abstract class UnsealedEliminator[-A, +B, -C, +D]
    extends Eliminator[A, B, C, D]

  def apply[A, B, C, D](
    left: A =#> B,
    tLeft: A => D,
    right: C =#> D,
    tRight: C => B,
  ): Axn[Eliminator[A, B, C, D]] = {
    Axn.unsafe.delay {
      this.unsafe(left, tLeft, right, tRight)
    }
  }

  private[choam] def unsafe[A, B, C, D](
    left: A =#> B,
    tLeft: A => D,
    right: C =#> D,
    tRight: C => B,
  ): Eliminator[A, B, C, D] = {
    new EliminatorImpl[A, B, C, D](left, tLeft, right, tRight) {}
  }

  /**
   * Tags the result as a `Left(_)`, if it's from
   * the underlying operation; or as a `Right(_)`,
   * if it's from the exchanger.
   *
   * Mainly for testing and debugging.
   */
  private[choam] final def tagged[A, B, C, D](
    left: A =#> B,
    tLeft: A => D,
    right: C =#> D,
    tRight: C => B,
  ): Axn[Eliminator[A, Either[B, B], C, Either[D, D]]] = {
    apply[A, Either[B, B], C, Either[D, D]](
      left.map(Left(_)),
      tLeft.andThen(Right(_)),
      right.map(Left(_)),
      tRight.andThen(Right(_)),
    )
  }
}
