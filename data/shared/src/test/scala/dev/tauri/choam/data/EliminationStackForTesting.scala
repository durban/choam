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
package data

import core.Eliminator

/**
 * This is like `EliminationStack2`, but:
 *
 * (1) it implements `Stack`, and
 * (2) it only uses the public API of `Eliminator`.
 */
final class EliminationStackForTesting[A] private (
  underlying: TreiberStack[A],
  eliminator: Eliminator[A, Unit, Any, Option[A]],
) extends Stack[A] {

  override def push: Rxn[A, Unit] =
    eliminator.leftOp

  override def tryPop: Axn[Option[A]] =
    eliminator.rightOp

  override def size: Axn[Int] =
    underlying.size
}

final object EliminationStackForTesting {
  def apply[A]: Axn[EliminationStackForTesting[A]] = {
    TreiberStack[A].flatMapF { underlying =>
      Eliminator[A, Unit, Any, Option[A]](
        underlying.push,
        Some(_),
        underlying.tryPop,
        _ => (),
      ).map { eliminator =>
        new EliminationStackForTesting(underlying, eliminator)
      }
    }
  }
}
