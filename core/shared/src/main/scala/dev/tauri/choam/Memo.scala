/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import core.{ Rxn, RxnImpl, Ref }

sealed abstract class Memo[F[_], A] {
  def getOrInit: F[A]
}

private object Memo {

  final def rxn[A](axn: Rxn[A], str: AllocationStrategy): Rxn[Memo[Rxn, A]] = {
    val init = newInitializer[A](axn.impl)
    Ref[A](init, str).map { st => new MemoImpl[Rxn, A](st) }
  }

  final def txn[A](txn: stm.Txn[A], str: AllocationStrategy): stm.Txn[Memo[stm.Txn, A]] = {
    val init = newInitializer[A](txn.impl)
    Ref.tRef[A](init, str.withStm(true)).map { st => new MemoImpl[stm.Txn, A](st) }
  }

  private[this] final class MemoImpl[R[a] >: RxnImpl[a], A](
    state: Ref[A],
  ) extends Memo[R, A] {

    final override def getOrInit: R[A] = {
      state.getImpl.flatMap { ov =>
        if (ov.isInstanceOf[Initializer[?]]) {
          ov.asInstanceOf[Initializer[A]].act.flatTap(state.set)
        } else {
          Rxn.pure(ov.asInstanceOf[A])
        }
      }
    }
  }

  /**
   * We need a wrapper (which is not accessible to user code),
   * so that we can reliably distinguish the `Rxn` to memoize
   * from its result.
   */
  private[this] final class Initializer[A](val act: RxnImpl[A])

  private[this] final def newInitializer[A](act: RxnImpl[A]): A = {
    (new Initializer(act)).asInstanceOf[A]
  }
}
