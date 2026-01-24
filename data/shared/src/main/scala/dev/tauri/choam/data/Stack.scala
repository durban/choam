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
package data

import cats.Monad
import cats.syntax.all._

import core.{ Rxn, Reactive }

sealed trait Stack[A] {
  def push(a: A): Rxn[Unit]
  def poll: Rxn[Option[A]]
  def peek: Rxn[Option[A]]
  private[choam] def size: Rxn[Int] // TODO: Do we want this? If yes, is it correct even on elim. stack?
}

/**
 * Various stacks
 *
 * The operations that may fail (e.g., trying to pop
 * an element from an empty stack) return an `Option`
 * (e.g., removing from an empty stack returns `None`).
 *
 * Method summary of the various operations:
 *
 * |         | `Rxn` (may fail)    | `Rxn` (succeeds) |
 * |---------|---------------------|------------------|
 * | insert  | -                   | `push`           |
 * | remove  | `poll`              | -                |
 * | examine | `peek`              | -                |
 *
 * @see [[dev.tauri.choam.async.AsyncStack$ AsyncStack]]
 *      for asynchronous (possibly fiber-blocking)
 *      variants of these methods
 */
object Stack {

  private[choam] trait UnsealedStack[A]
    extends Stack[A]

  final def apply[A]: Rxn[Stack[A]] =
    apply[A](AllocationStrategy.Default)

  final def apply[A](str: AllocationStrategy): Rxn[Stack[A]] =
    TreiberStack[A](str)

  // TODO: on JS, we could just return a TreiberStack
  final def eliminationStack[A]: Rxn[Stack[A]] = {
    EliminationStack[A]
  }

  private[choam] def fromList[F[_], A](mkEmpty: Rxn[Stack[A]])(as: List[A])(implicit F: Reactive[F]): F[Stack[A]] = {
    implicit val monadF: Monad[F] = F.monad
    mkEmpty.run[F].flatMap { stack =>
      as.traverse { a =>
        stack.push(a).run[F]
      }.as(stack)
    }
  }

  private[choam] def popAll[F[_], A](s: Stack[A])(implicit F: Reactive[F]): F[List[A]] = {
    F.monad.tailRecM(List.empty[A]) { lst =>
      F.monad.map(s.poll.run[F]) {
        case None => Right(lst.reverse)
        case Some(a) => Left(a :: lst)
      }
    }
  }
}
