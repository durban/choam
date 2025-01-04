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
package refs

import internal.mcas.RefIdGen

trait Ref2[A, B] {

  def _1: Ref[A]

  def _2: Ref[B]

  def consistentRead: Axn[(A, B)] =
    Ref.consistentRead(this._1, this._2)
}

object Ref2 extends Ref2Platform {

  final def p1p1[A, B](a: A, b: B): Axn[Ref2[A, B]] =
    Rxn.unsafe.delayContext { ctx => unsafeP1P1(a, b, ctx.refIdGen) }

  final def p2[A, B](a: A, b: B): Axn[Ref2[A, B]] =
    Rxn.unsafe.delayContext { ctx => unsafeP2(a, b, ctx.refIdGen) }

  final def unsafeP1P1[A, B](a: A, b: B): Ref2[A, B] =
    this.unsafeP1P1(a, b, RefIdGen.global)

  final def unsafeP2[A, B](a: A, b: B): Ref2[A, B] =
    this.unsafeP2(a, b, RefIdGen.global)

  final def unapply[A, B](r: Ref2[A, B]): Some[(Ref[A], Ref[B])] =
    Some((r._1, r._2))
}
