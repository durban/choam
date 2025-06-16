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

sealed abstract class Memo[A] {
  def getOrInit: Axn[A]
}

private object Memo {

  final def apply[A](axn: Axn[A], str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Axn[Memo[A]] = {
    val init = newInitializer[A](axn)
    Ref[A](init, str).map { st => new MemoImpl(st) }
  }

  private[this] final class MemoImpl[A](
    state: Ref[A],
  ) extends Memo[A] {

    final override def getOrInit: Axn[A] = {
      state.updateAndGetWith { ov =>
        if (ov.isInstanceOf[Initializer[?]]) {
          ov.asInstanceOf[Initializer[A]].act
        } else {
          Rxn.pure(ov.asInstanceOf[A])
        }
      }
    }
  }

  /**
   * We need a wrapper (which is not accessible to user code),
   * so that we can reliably distinguish the `Axn` to memoize
   * from its result.
   */
  private[this] final class Initializer[A](val act: Axn[A])

  private[this] final def newInitializer[A](act: Axn[A]): A = {
    (new Initializer(act)).asInstanceOf[A]
  }
}
