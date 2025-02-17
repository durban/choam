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

import core.EliminatorImpl

// TODO:0.5: make this implement `Stack`; make it private
private[choam] final class EliminationStack2[A](underlying: Stack[A])
  extends EliminatorImpl[A, Unit, Any, Option[A]](underlying.push, Some(_), underlying.tryPop, _ => ()) {

  final def push: Rxn[A, Unit] =
    this.leftOp

  final def tryPop: Axn[Option[A]] =
    this.rightOp

  final def size: Axn[Int] =
    this.underlying.size
}

private object EliminationStack2 {

  def apply[A]: Axn[EliminationStack2[A]] =
    apply(Ref.AllocationStrategy.Default)

  def apply[A](str: Ref.AllocationStrategy): Axn[EliminationStack2[A]] = {
    Stack.treiberStack[A](str).flatMapF { ul =>
      Axn.unsafe.delay { new EliminationStack2[A](ul) }
    }
  }
}
