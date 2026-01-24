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

import unsafe.RxnLocal

sealed trait TxnLocal[A] {
  def get: Txn[A]
  def set(a: A): Txn[Unit]
  def update(f: A => A): Txn[Unit]
  def getAndUpdate(f: A => A): Txn[A]
}

object TxnLocal {

  private[choam] trait UnsealedTxnLocal[A] extends TxnLocal[A]

  private[choam] trait UnsealedTxnLocalArray[A] extends TxnLocal.Array[A]

  sealed trait Array[A] {
    def size: Int
    // TODO: def get(idx: Int): G[Any, Option[A]]
    // TODO: def set(idx: Int, nv: A): G[Any, Boolean]
    def unsafeGet(idx: Int): Txn[A]
    def unsafeSet(idx: Int, nv: A): Txn[Unit]
  }

  private[stm] final def newLocal[A](initial: A): Txn[TxnLocal[A]] = {
    RxnLocal.newTxnLocal(initial)
  }

  private[stm] final def newLocalArray[A](size: Int, initial: A): Txn[TxnLocal.Array[A]] = {
    RxnLocal.newTxnLocalArray(size, initial)
  }
}
