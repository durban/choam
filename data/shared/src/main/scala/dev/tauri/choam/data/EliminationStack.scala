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

import core.Exchanger

// Note: this logic is duplicated below in `DebugStackImpl`
private final class EliminationStack[A] private (
  primary: TreiberStack[A],
  elimination: Exchanger[Unit, A],
) extends Stack[A] {

  final override val push: Rxn[A, Unit] =
    primary.push + elimination.dual.exchange

  final override val tryPop: Axn[Option[A]] =
    (primary.tryPop + elimination.exchange.provide(()).map(Some(_)))

  final override def size: Axn[Int] =
    primary.size
}

private object EliminationStack {

  def apply[A](str: Ref.AllocationStrategy): Axn[Stack[A]] = {
    (TreiberStack[A](str) * Rxn.unsafe.exchanger[Unit, A]).map {
      case (tStack, exc) =>
        new EliminationStack(tStack, exc)
    }
  }

  def fromList[F[_], A](as: List[A], str: Ref.AllocationStrategy)(implicit F: Reactive[F]): F[Stack[A]] = {
    Stack.fromList(this.apply[A](str))(as)
  }

  private[data] def debug[A]: Axn[DebugStack[A]] = {
    (TreiberStack[A](Ref.AllocationStrategy.Default) * Rxn.unsafe.exchanger[Unit, A]).map {
      case (tStack, exc) =>
        new DebugStackImpl(tStack, exc)
    }
  }

  private[data] sealed abstract class DebugStack[A] extends Stack[A] {
    def pushDebug: Rxn[A, DebugResult[Unit]]
    def tryPopDebug: Axn[Option[DebugResult[A]]]
  }

  private[data] sealed abstract class DebugResult[A] {
    def value: A
  }

  private[data] final case class FromStack[A](override val value: A)
    extends DebugResult[A]
  private[data] final case class Exchanged[A](override val value: A)
    extends DebugResult[A]

  private[this] val _fromStackUnit: DebugResult[Unit] =
    FromStack(())

  private[this] val _exchangedUnit: DebugResult[Unit] =
    Exchanged(())

  // Note: this logic is duplicated above in `EliminationStack`
  private final class DebugStackImpl[A] private[EliminationStack] (
    primary: TreiberStack[A],
    elimination: Exchanger[Unit, A],
  ) extends DebugStack[A] {

    final override val pushDebug: Rxn[A, DebugResult[Unit]] = {
      primary.push.as(_fromStackUnit) + (
        elimination.dual.exchange.as(_exchangedUnit)
      )
    }

    final override val tryPopDebug: Axn[Option[DebugResult[A]]] = {
      (primary.tryPop.map(_.map(FromStack(_))) + (
        elimination.exchange.provide(()).map(a => Some(Exchanged(a))))
      )
    }

    final override def push: Rxn[A, Unit] =
      pushDebug.map(_.value)

    final override def tryPop: Axn[Option[A]] =
      tryPopDebug.map(_.map(_.value))

    final override def size: Axn[Int] =
      primary.size
  }
}
