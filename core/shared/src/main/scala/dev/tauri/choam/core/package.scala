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

package object core {

  /** Symbolic alias for `Rxn` */
  final type =#>[-A, +B] = Rxn[B] // TODO: remove this

  /**
   * The description of an effect, which (when executed),
   * results in a value of type `A`; during execution,
   * it may update any number of [[dev.tauri.choam.core.Ref Ref]]s
   * atomically (and it may also create new `Ref`s).
   *
   * This type forms a `Monad`. However, when composing
   * these kinds of effects, also consider using [[dev.tauri.choam.core.Rxn Rxn]]
   * and `>>>` or `*>` instead of `flatMap`.
   *
   * The relation between [[Axn]] and [[dev.tauri.choam.core.Rxn Rxn]] is approximately
   * `Axn[A] ≡ Rxn[Any, A]`; or, alternatively
   * `Rxn[A, B] ≡ (A => Axn[B])` (see [[dev.tauri.choam.core.Rxn!.toFunction toFunction]]).
   */
  final type Axn[+A] = Rxn[A] // short for 'astaxanthin'
}
