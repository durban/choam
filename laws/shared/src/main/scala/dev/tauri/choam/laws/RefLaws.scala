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

import cats.kernel.Order
import cats.laws.IsEq
import cats.laws.IsEqArrow
import cats.syntax.all._

import core.{ Rxn, Ref }

sealed trait RefLaws {

  def equalsItself[A](r: Ref[A]): IsEq[Boolean] =
    Order[Ref[A]].eqv(r, r) <-> true

  def uniqueIdsSameType[A](x: Ref[A], y: Ref[A]): IsEq[Boolean] = {
    ((x eq y) || (x.loc.id =!= y.loc.id)) <-> true
  }

  def uniqueIdsDifferentType[A, B](x: Ref[A], y: Ref[B]): IsEq[Boolean] = {
    ((x eq y) || (x.loc.id =!= y.loc.id)) <-> true
  }

  def hashCodeBasedOnId[A](r: Ref[A]): IsEq[Int] =
    r.## <-> r.loc.id.toInt

  def orderConsistentWithIdentity[A](x: Ref[A], y: Ref[A]): IsEq[Boolean] =
    Order[Ref[A]].eqv(x, y) <-> (x eq y)

  def modifyWithPureIsModify[A, C](x: Ref[A], f: A => A, g: A => C): IsEq[Rxn[C]] = {
    val uw = x.modifyWith { a => Rxn.pure((f(a), g(a))) }
    val u = x.modify { a => (f(a), g(a)) }
    def restoreRefAtEnd(rxn: Rxn[C]): Rxn[C] = {
      x.get.flatMap { orig =>
        rxn.postCommit(x.set(orig))
      }
    }
    restoreRefAtEnd(uw) <-> restoreRefAtEnd(u)
  }
}

object RefLaws {
  def newRefLaws: RefLaws =
    new RefLaws {}
}
