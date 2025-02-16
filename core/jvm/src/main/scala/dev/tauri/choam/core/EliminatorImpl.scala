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

/**
 * Internally, we can use this by subclassing (to avoid an extra object)
 *
 * Note: this is duplicated on JS (where it doesn't do anything).
 */
@nowarn("cat=deprecation")
private[choam] abstract class EliminatorImpl[-A, +B, -C, +D] private[choam] (
  underlyingLeft: A =#> B,
  transformLeft: A => D,
  underlyingRight: C =#> D,
  transformRight: C => B,
) extends Eliminator.UnsealedEliminator[A, B, C, D] {

  private[this] val exchanger: Exchanger[A, C] =
    Exchanger.unsafe[A, C]

  final override val leftOp: A =#> B =
    underlyingLeft + exchanger.exchange.map(transformRight)

  final override val rightOp: C =#> D =
    underlyingRight + exchanger.dual.exchange.map(transformLeft)
}
