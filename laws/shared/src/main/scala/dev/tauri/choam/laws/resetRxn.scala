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
package laws

import cats.syntax.all._

import core.{ Rxn, Ref }

private final case class ResetRxn[+B](
  rxn: Rxn[B],
  refs: Set[ResetRef[?]] = Set.empty,
) {

  def toRxn: Rxn[B] =
    rxn.postCommit(this.resetAll)

  private[this] final def resetAll: Rxn[Unit] = {
    refs.toList.traverse_(resetRef => resetRef.reset)
  }

  def + [Y >: B](that: ResetRxn[Y]): ResetRxn[Y] =
    ResetRxn(this.rxn + that.rxn, this.refs union that.refs)

  def *> [C](that: ResetRxn[C]): ResetRxn[C] =
    ResetRxn(this.rxn *> that.rxn, this.refs union that.refs)

  def * [C](that: ResetRxn[C]): ResetRxn[(B, C)] =
    ResetRxn(this.rxn * that.rxn, this.refs union that.refs)

  def map[C](f: B => C): ResetRxn[C] =
    ResetRxn(this.rxn.map(f), this.refs)
}

private final case class ResetRef[A](ref: Ref[A], resetTo: A) {
  final def reset: Rxn[Unit] = {
    ref.set(resetTo)
  }
}
