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
package async

import core.{ =#>, Rxn, Axn, AsyncReactive }
import data.Stack

sealed trait AsyncStack[A] {
  def push: Rxn[A, Unit]
  def pop[F[_]](implicit F: AsyncReactive[F]): F[A]
  def tryPop: Axn[Option[A]]
}

object AsyncStack {

  final def treiberStack[A]: Axn[AsyncStack[A]] =
    Stack.treiberStack[A].flatMapF(fromSyncStack[A])

  final def eliminationStack[A]: Axn[AsyncStack[A]] =
    Stack.eliminationStack[A].flatMapF(fromSyncStack[A])

  private[this] final def fromSyncStack[A](stack: Stack[A]): Axn[AsyncStack[A]] = {
    WaitList(stack.tryPop, stack.push).map { wl =>
      new AsyncStack[A] {
        final override def push: A =#> Unit =
          wl.set0.void
        final override def pop[F[_]](implicit F: AsyncReactive[F]): F[A] =
          wl.asyncGet
        final override def tryPop: Axn[Option[A]] =
          stack.tryPop
      }
    }
  }
}
