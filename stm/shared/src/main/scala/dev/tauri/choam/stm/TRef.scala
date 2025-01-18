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
package stm

import internal.mcas.Mcas

sealed trait TRef[F[_], A] {
  def get: Txn[F, A]
  def set(a: A): Txn[F, Unit]
  def update(f: A => A): Txn[F, Unit]
  def modify[B](f: A => (A, B)): Txn[F, B]
  def getAndSet(a: A): Txn[F, A]
  def getAndUpdate(f: A => A): Txn[F, A]
  def updateAndGet(f: A => A): Txn[F, A]
}

object TRef {

  private[choam] trait UnsealedTRef[F[_], A] extends TRef[F, A]

  final def apply[F[_], A](a: A): Txn[F, TRef[F, A]] =
    core.Rxn.unsafe.delayContext(unsafe[F, A](a)).castF[F]

  private[choam] final def unsafe[F[_], A](a: A)(ctx: Mcas.ThreadContext): TRef[F, A] = {
    val id = ctx.refIdGen.nextId()
    new TRefImpl[F, A](a, id)
  }
}
