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
package internal
package refs

import cats.data.Chain

import core.Ref

private[choam] final class EmptyRefArray[A] extends Ref.UnsealedArray0[A] with stm.TArray.UnsealedTArray[A] {

  final override def length: Int =
    0

  final override def toString: String =
    s"Ref.Array[0]@${java.lang.Long.toHexString(0L)}" // TODO: this is incorrect for TArray

  final override def refs: Chain[Ref[A]] =
    Chain.empty

  private[this] final def throwOob(idx: Int): Nothing =
    throw new IndexOutOfBoundsException(s"Index ${idx} out of bounds for length 0")

  final override def unsafeApply(idx: Int): Ref[A] =
    throwOob(idx)

  final override def unsafeGet(idx: Int): stm.Txn[A] =
    throwOob(idx)

  final override def unsafeSet(idx: Int, nv: A): stm.Txn[Unit] =
    throwOob(idx)

  final override def unsafeUpdate(idx: Int, f: A => A): stm.Txn[Unit] =
    throwOob(idx)

  final override def get(idx: Int): stm.Txn[Option[A]] =
    stm.Txn.none

  final override def set(idx: Int, nv: A): stm.Txn[Boolean] =
    stm.Txn._false

  final override def update(idx: Int, f: A => A): stm.Txn[Boolean] =
    stm.Txn._false
}
