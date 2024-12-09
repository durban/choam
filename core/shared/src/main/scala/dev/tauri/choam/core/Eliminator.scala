/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
  def leftOp: A =#> B
  def rightOp: C =#> D
}

object Eliminator {

  // TODO: on JS, the whole thing should be a no-op

  def apply[A, B, C, D](
    left: A =#> B,
    tLeft: A => D,
    right: C =#> D,
    tRight: C => B,
  ): Axn[Eliminator[A, B, C, D]] = {
    Axn.unsafe.delay {
      new EliminatorImpl[A, B, C, D](left, tLeft, right, tRight) {}
    }
  }
}

/** Internally, we can use this by subclassing (to avoid an extra object) */
private[choam] abstract class EliminatorImpl[-A, +B, -C, +D] private[choam] (
  underlyingLeft: A =#> B,
  transformLeft: A => D,
  underlyingRight: C =#> D,
  transformRight: C => B,
) extends Eliminator[A, B, C, D] {

  private[this] val exchanger: Exchanger[A, C] =
    Exchanger.unsafe[A, C]

  final override val leftOp: A =#> B =
    underlyingLeft + exchanger.exchange.map(transformRight)

  final override val rightOp: C =#> D =
    underlyingRight + exchanger.dual.exchange.map(transformLeft)
}
