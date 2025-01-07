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

import internal.mcas.MemoryLocation

private trait TRefImplBase[F[_], A] extends MemoryLocation[A] with TRef.UnsealedTRef[F, A] {

  final override def set(a: A): Txn[F, Unit] =
    core.Rxn.loc.upd[A, Unit, Unit](this) { (_, _) => (a, ()) }.castF[F]

  final override def update(f: A => A): Txn[F, Unit] =
    this.modify { ov => (f(ov), ()) }

  final override def modify[B](f: A => (A, B)): Txn[F, B] =
    core.Rxn.loc.upd[A, Any, B](this) { (ov, _) => f(ov) }.castF[F]

  final override def getAndSet(a: A): Txn[F, A] =
    this.modify { ov => (a, ov) }
}
