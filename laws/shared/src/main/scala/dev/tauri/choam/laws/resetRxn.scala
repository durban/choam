/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package laws

private final case class ResetRxn[-A, +B](
  rxn: Rxn[A, B],
  refs: Set[ResetRef[_]] = Set.empty,
) {

  def toRxn: Rxn[A, B] =
    rxn.postCommit(Rxn.unsafe.delay(_ => unsafeResetAll()))

  def unsafeResetAll(): Unit = {
    refs.foreach(_.unsafeReset())
  }

  def + [X <: A, Y >: B](that: ResetRxn[X, Y]): ResetRxn[X, Y] =
    ResetRxn(this.rxn + that.rxn, this.refs union that.refs)

  def >>> [C](that: ResetRxn[B, C]): ResetRxn[A, C] =
    ResetRxn(this.rxn >>> that.rxn, this.refs union that.refs)

  def * [X <: A, C](that: ResetRxn[X, C]): ResetRxn[X, (B, C)] =
    ResetRxn(this.rxn * that.rxn, this.refs union that.refs)

  def map[C](f: B => C): ResetRxn[A, C] =
    ResetRxn(this.rxn.map(f), this.refs)
}

private final case class ResetRef[A](ref: Ref[A], resetTo: A) {
  def unsafeReset(): Unit = {
    ref.loc.unsafeSetVolatile(resetTo)
  }
}