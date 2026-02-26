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
package laws

import cats.kernel.Eq
import cats.laws.IsEq
import cats.laws.IsEqArrow
import cats.syntax.all._

import core.Ref

sealed trait RefLaws {

  def equalsItself[A](r: Ref[A]): IsEq[Boolean] =
    Eq[Ref[A]].eqv(r, r) <-> true

  def uniqueIdsSameType[A](x: Ref[A], y: Ref[A]): IsEq[Boolean] = {
    ((x eq y) || (x.loc.id =!= y.loc.id)) <-> true
  }

  def uniqueIdsDifferentType[A, B](x: Ref[A], y: Ref[B]): IsEq[Boolean] = {
    ((x eq y) || (x.loc.id =!= y.loc.id)) <-> true
  }

  def hashCodeBasedOnId[A](r: Ref[A]): IsEq[Int] =
    r.## <-> r.loc.id.toInt

  def orderConsistentWithIdentity[A](x: Ref[A], y: Ref[A]): IsEq[Boolean] =
    Eq[Ref[A]].eqv(x, y) <-> (x eq y)
}

object RefLaws {
  def newRefLaws: RefLaws =
    new RefLaws {}
}
