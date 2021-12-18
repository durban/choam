/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.kernel.Order
import cats.laws.IsEq
import cats.laws.IsEqArrow
import cats.syntax.all._

trait RefLaws {

  def equalsItself[A](r: Ref[A]): IsEq[Boolean] =
    Order[Ref[A]].eqv(r, r) <-> true

  def uniqueIdsSameType[A](x: Ref[A], y: Ref[A]): IsEq[Boolean] = {
    ((x eq y) || (
      (x.loc.id0 =!= y.loc.id0) ||
      (x.loc.id1 =!= y.loc.id1) ||
      (x.loc.id2 =!= y.loc.id2) ||
      (x.loc.id3 =!= y.loc.id3)
    )) <-> true
  }

  def uniqueIdsDifferentType[A, B](x: Ref[A], y: Ref[B]): IsEq[Boolean] = {
    ((x eq y) || (
      (x.loc.id0 =!= y.loc.id0) ||
      (x.loc.id1 =!= y.loc.id1) ||
      (x.loc.id2 =!= y.loc.id2) ||
      (x.loc.id3 =!= y.loc.id3)
    )) <-> true
  }

  def orderConsistentWithIdentity[A](x: Ref[A], y: Ref[A]): IsEq[Boolean] =
    Order[Ref[A]].eqv(x, y) <-> (x eq y)

  def updWithRetIsUpd[A, B, C](x: Ref[A], f: A => A, g: B => C): IsEq[Rxn[B, C]] = {
    val uw = x.updWith[B, C] { (a, b) => Rxn.ret((f(a), g(b))) }
    val u = x.upd[B, C] { (a, b) => (f(a), g(b)) }
    uw <-> u
  }
}
