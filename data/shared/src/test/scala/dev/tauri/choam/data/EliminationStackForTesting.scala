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
package data

import core.{ Rxn, Ref, Eliminator }

/**
 * This is like `EliminationStack2`, but:
 *
 * (1) it implements `Stack`, and
 * (2) it only uses the public API of `Eliminator`.
 */
final class EliminationStackForTesting[A] private (
  underlying: TreiberStack[A],
  eliminator: Eliminator[A, Unit, Any, Option[A]],
) extends Stack.UnsealedStack[A] {

  override def push(a: A): Rxn[Unit] =
    eliminator.leftOp(a)

  override def tryPop: Rxn[Option[A]] =
    eliminator.rightOp(null)

  override def tryPeek: Rxn[Option[A]] =
    underlying.tryPeek

  override def size: Rxn[Int] =
    underlying.size
}

final object EliminationStackForTesting {
  def apply[A]: Rxn[EliminationStackForTesting[A]] = {
    TreiberStack[A](Ref.AllocationStrategy.Default).flatMap { underlying =>
      Eliminator[A, Unit, Any, Option[A]](
        underlying.push,
        Some(_),
        _ => underlying.tryPop,
        _ => (),
      ).map { eliminator =>
        new EliminationStackForTesting(underlying, eliminator)
      }
    }
  }
}
