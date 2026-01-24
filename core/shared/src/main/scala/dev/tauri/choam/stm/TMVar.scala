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
package stm

/**
 * MVar from the "Composable Memory Transactions" paper
 * by Harris, Marlow, Peyton Jones, and Herlihy.
 */
private sealed abstract class TMVar[A] { // TODO: figure out if we want this public (probably not)

  def take: Txn[A]

  def put(a: A): Txn[Unit]

  final def tryPut(a: A): Txn[Boolean] = {
    this.put(a).as(true) orElse Txn._false
  }

  final def tryTake: Txn[Option[A]] = {
    this.take.map(Some(_)) orElse Txn.none
  }
}

private object TMVar {

  final def apply[A]: Txn[TMVar[A]] = {
    TRef[Option[A]](None).map { r =>
      new TMVarImpl(r)
    }
  }

  private[this] final class TMVarImpl[A](state: TRef[Option[A]]) extends TMVar[A] {

    final override def take: Txn[A] = state.get.flatMap {
      case None => Txn.retry
      case Some(a) => state.set(None).as(a)
    }

    final override def put(a: A): Txn[Unit] = state.get.flatMap {
      case None => state.set(Some(a))
      case Some(_) => Txn.retry
    }
  }
}
