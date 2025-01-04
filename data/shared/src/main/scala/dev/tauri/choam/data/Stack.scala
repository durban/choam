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

import cats.Monad
import cats.syntax.all._

abstract class Stack[A] { // TODO:0.5: make it a trait
  def push: Rxn[A, Unit]
  def tryPop: Axn[Option[A]]
  def size: Axn[Int]
}

object Stack {

  def treiberStack[A]: Axn[Stack[A]] =
    TreiberStack[A]

  // TODO: on JS, we could just return a TreiberStack
  def eliminationStack[A]: Axn[Stack[A]] =
    EliminationStack[A]

  private[choam] def eliminationStack2[A]: Axn[EliminationStack2[A]] =
    EliminationStack2[A]

  private[choam] def fromList[F[_], A](mkEmpty: Axn[Stack[A]])(as: List[A])(implicit F: Reactive[F]): F[Stack[A]] = {
    implicit val monadF: Monad[F] = F.monad
    mkEmpty.run[F].flatMap { stack =>
      as.traverse { a =>
        stack.push[F](a)
      }.as(stack)
    }
  }

  private[choam] def popAll[F[_], A](s: Stack[A])(implicit F: Reactive[F]): F[List[A]] = {
    F.monad.tailRecM(List.empty[A]) { lst =>
      F.monad.map(s.tryPop.run[F]) {
        case None => Right(lst.reverse)
        case Some(a) => Left(a :: lst)
      }
    }
  }
}
