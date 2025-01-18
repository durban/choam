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

sealed trait TPromise[F[_], A] { // TODO: separate read and write side
  def get: Txn[F, A]
  def tryGet: Txn[F, Option[A]]
  def complete(a: A): Txn[F, Boolean]
}

object TPromise {

  final def apply[F[_], A]: Txn[F, TPromise[F, A]] =
    Txn.unsafe.delayContext(unsafe[F, A])

  private[choam] final def unsafe[F[_], A](ctx: Mcas.ThreadContext): TPromise[F, A] = {
    val ref = TRef.unsafe[F, Option[A]](None)(ctx)
    new TPromiseImpl[F, A](ref)
  }

  private final class TPromiseImpl[F[_], A](
    ref: TRef[F, Option[A]],
  ) extends TPromise[F, A] {

    final override val get: Txn[F, A] = {
      ref.get.flatMap {
        case None => Txn.retry
        case Some(a) => Txn.pure(a)
      }
    }

    final override def tryGet: Txn[F, Option[A]] = {
      ref.get
    }

    final override def complete(a: A): Txn[F, Boolean] = {
      ref.modify {
        case None => (Some(a), true)
        case s @ Some(_) => (s, false)
      }
    }
  }
}
