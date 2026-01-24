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

import core.Ref

sealed trait TArray[A] {

  def length: Int

  def unsafeGet(idx: Int): Txn[A]
  def unsafeSet(idx: Int, nv: A): Txn[Unit]
  def unsafeUpdate(idx: Int)(f: A => A): Txn[Unit]

  def get(idx: Int): Txn[Option[A]]
  def set(idx: Int, nv: A): Txn[Boolean]
  def update(idx: Int)(f: A => A): Txn[Boolean]

  // TODO: def refs: Vector[TRef[A]]
}

object TArray {

  private[choam] trait UnsealedTArray[A] extends TArray[A]

  private[choam] final val DefaultAllocationStrategy: AllocationStrategy =
    AllocationStrategy.SparseFlat.withStm(true)

  final def apply[A](
    length: Int,
    initial: A,
  ): Txn[TArray[A]] = {
    apply[A](length, initial, DefaultAllocationStrategy)
  }

  private[choam] final def apply[A](
    length: Int,
    initial: A,
    strategy: AllocationStrategy,
  ): Txn[TArray[A]] = {
    val stmStr = strategy.withStm(true)
    Ref.safeTArrayImpl(size = length, initial = initial, str = stmStr)
  }
}
