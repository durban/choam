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

import internal.mcas.Mcas

sealed trait TRef[A] {

  def get: Txn[A]
  def set(a: A): Txn[Unit]
  def update(f: A => A): Txn[Unit]
  def modify[B](f: A => (A, B)): Txn[B]
  def getAndSet(a: A): Txn[A]
  def getAndUpdate(f: A => A): Txn[A]
  def updateAndGet(f: A => A): Txn[A]
  def flatModify[B](f: A => (A, Txn[B])): Txn[B]

  private[choam] def refImpl: core.Ref[A]
}

object TRef {

  private[choam] trait UnsealedTRef[A] extends TRef[A]

  final def apply[A](a: A): Txn[TRef[A]] =
    Txn.unsafe.delayContext(unsafe[A](a))

  private[choam] final def unsafe[A](a: A)(ctx: Mcas.ThreadContext): TRef[A] =
    impl(a, ctx.refIdGen.nextId())

  /** Creates a `Ref` which is also a `TRef`; use with caution! */
  private[choam] final def unsafeRefWithId[A](a: A, id: Long): core.Ref[A] with TRef[A] =
    impl(a, id)

  private[this] final def impl[A](a: A, id: Long): TRefImpl[A] = {
    new TRefImpl[A](a, id)
  }
}
