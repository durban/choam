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

import core.{ Rxn, AsyncReactive }
import data.Stack

sealed trait AsyncStack[A] extends Stack.UnsealedStack[A] {
  def pop[F[_]](implicit F: AsyncReactive[F]): F[A]
}

/**
 * Various asynchronous stacks
 *
 * Adds asynchronous variants to the methods of
 * [[dev.tauri.choam.data.Stack$ Stack]] (see the last column
 * of the table below). These operations have a result
 * type in an asynchronous `F`, and may be fiber-blocking.
 * For example, asynchronously removing an element from
 * an empty stack fiber-blocks until the stack is non-empty
 * (or until the fiber is cancelled).
 *
 * Method summary of the various operations:
 *
 * |         | `Rxn` (may fail)    | `Rxn` (succeeds) | `F` (may block)        |
 * |---------|---------------------|------------------|------------------------|
 * | insert  | -                   | `push`           | -                      |
 * | remove  | `poll`              | -                | `pop`                  |
 * | examine | `peek`              | -                | -                      |
 *
 * @see [[dev.tauri.choam.data.Stack$ Stack]]
 *      for the synchronous methods (all except
 *      the last column of this table)
 */
object AsyncStack {

  final def apply[A]: Rxn[AsyncStack[A]] =
    Stack[A].flatMap(fromSyncStack[A])

  final def apply[A](str: AllocationStrategy): Rxn[AsyncStack[A]] =
    Stack[A](str).flatMap(fromSyncStack[A])

  final def eliminationStack[A]: Rxn[AsyncStack[A]] =
    Stack.eliminationStack[A].flatMap(fromSyncStack[A])

  private[this] final def fromSyncStack[A](stack: Stack[A]): Rxn[AsyncStack[A]] = {
    WaitList(stack.poll, stack.push, stack.peek).map { wl =>
      new AsyncStack[A] {
        final override def push(a: A): Rxn[Unit] =
          wl.set(a).void
        final override def pop[F[_]](implicit F: AsyncReactive[F]): F[A] =
          wl.asyncGet
        final override def poll: Rxn[Option[A]] =
          wl.tryGet
        final override def peek: Rxn[Option[A]] =
          wl.tryGetReadOnly
        private[choam] final override def size: Rxn[Int] =
          stack.size
      }
    }
  }
}
