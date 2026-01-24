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

import cats.laws.IsEq
import cats.laws.IsEqArrow

import core.Rxn
import core.Rxn.unsafe.retry

sealed trait RxnLaws {

  // This is to make sure our `Arbitrary` instance
  // only creates deterministic `Rxn`s.
  def equalsItself[B](rxn: Rxn[B]): IsEq[Rxn[B]] = {
    rxn <-> rxn
  }

  def asIsMap[B, C](rxn: Rxn[B], c: C): IsEq[Rxn[C]] =
    rxn.as(c) <-> rxn.map[C](_ => c)

  def voidIsMap[B](rxn: Rxn[B]): IsEq[Rxn[Unit]] =
    rxn.void <-> rxn.map[Unit](_ => ())

  def distributiveAndThenChoice1[B, C](x: Rxn[B], y: Rxn[C], z: Rxn[C]): IsEq[Rxn[C]] =
    (x *> (y + z)) <-> ((x *> y) + (x *> z))

  def distributiveAndThenChoice2[B, C](x: Rxn[B], y: Rxn[B], z: Rxn[C]): IsEq[Rxn[C]] =
    ((x + y) *> z) <-> ((x *> z) + (y *> z))

  def distributiveAndAlsoChoice1[B, D](x: Rxn[B], y: Rxn[D], z: Rxn[D]): IsEq[Rxn[(B, D)]] =
    (x * (y + z)) <-> ((x * y) + (x * z))

  def distributiveAndAlsoChoice2[B, D](x: Rxn[B], y: Rxn[B], z: Rxn[D]): IsEq[Rxn[(B, D)]] =
    ((x + y) * z) <-> ((x * z) + (y * z))

  def associativeAndAlso[B, D, F](x: Rxn[B], y: Rxn[D], z: Rxn[F]): IsEq[Rxn[((B, D), F)]] = {
    ((x * y) * z) <-> (x * (y * z)).map(
      b_df => ((b_df._1, b_df._2._1), b_df._2._2)
    )
  }

  // TODO: does this always hold?
  def choiceRetryNeutralRight[B](x: Rxn[B]): IsEq[Rxn[B]] =
    (x + retry[B]) <-> x

  // TODO: does this always hold?
  def choiceRetryNeutralLeft[B](x: Rxn[B]): IsEq[Rxn[B]] =
    (retry[B] + x) <-> x

  // TODO: do these make a monoid with `+`?
}

object RxnLaws {
  def newRxnLaws: RxnLaws =
    new RxnLaws {}
}
