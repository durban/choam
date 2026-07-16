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
package async

import cats.Invariant

import core.{ Rxn, AsyncReactive }
import data.Stack

/**
 * Asynchronous operations for stacks
 *
 * @note For technical reasons `AsyncStack` is not
 *       a subtype of [[data.Stack]], however an
 *       implicit evidence witnessing this subtype
 *       relationship is available: [[AsyncStack.asyncStackIsStack]].
 *
 * @see [[asStack]] for explicitly converting to a [[data.Stack]]
 */
sealed trait AsyncStack[A] { this: Stack[A] =>

  def pop[F[_]](implicit F: AsyncReactive[F]): F[A]

  final def asStack: Stack[A] = this

  private[async] def impl: AsyncStack[A] with Stack[A]
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

  final def apply[A]: Rxn[AsyncStack[A] with Stack[A]] =
    Stack[A].flatMap(fromSyncStack[A])

  final def apply[A](str: AllocationStrategy): Rxn[AsyncStack[A] with Stack[A]] =
    Stack[A](str).flatMap(fromSyncStack[A])

  final def eliminationStack[A]: Rxn[AsyncStack[A] with Stack[A]] =
    Stack.eliminationStack[A].flatMap(fromSyncStack[A])

  implicit final def asyncStackIsStack[A]: AsyncStack[A] <:< Stack[A] =
    <:<.refl[AsyncStack[A]].asInstanceOf[AsyncStack[A] <:< Stack[A]]

  implicit final def invariantFunctorForDevTauriChoamAsyncAsyncStack: Invariant[AsyncStack] =
    _invariantFunctorInstance

  private[this] val _invariantFunctorInstance: Invariant[AsyncStack] = new Invariant[AsyncStack] {
    final override def imap[A, B](fa: AsyncStack[A])(f: A => B)(g: B => A): AsyncStack[B] = new AsyncStack[B] with Stack.UnsealedStack[B] {
      final override def push(b: B): Rxn[Unit] = fa.impl.push(g(b))
      final override def poll: Rxn[Option[B]] = fa.impl.poll.map(_.map(f))
      final override def peek: Rxn[Option[B]] = fa.impl.peek.map(_.map(f))
      private[choam] final override def size: Rxn[Int] = fa.impl.size
      private[async] final override def impl: AsyncStack[B] with Stack[B] = this
      final override def pop[F[_]](implicit F: AsyncReactive[F]): F[B] = F.monad.map(fa.pop)(f)
    }
  }

  private[this] final def fromSyncStack[A](stack: Stack[A]): Rxn[AsyncStack[A] with Stack[A]] = {
    WaitList(stack.poll, stack.push, stack.peek).map { wl =>
      new AsyncStack[A] with Stack.UnsealedStack[A] {
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
        private[async] final override def impl: AsyncStack[A] with Stack[A] =
          this
      }
    }
  }
}
