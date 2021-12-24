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

package dev.tauri.choam
package data

final class EliminationStack[A] private (
  primary: TreiberStack[A],
  elimination: Exchanger[Unit, A],
) extends Stack[A] {

  final override val push: Rxn[A, Unit] =
    primary.push + elimination.dual.exchange

  final override val tryPop: Axn[Option[A]] =
    (primary.unsafePop + elimination.exchange.provide(())).?
}

object EliminationStack {

  def apply[A]: Axn[Stack[A]] = {
    (TreiberStack[A] * Rxn.unsafe.exchanger[Unit, A]).map {
      case (tStack, exc) =>
        new EliminationStack(tStack, exc)
    }
  }
}
