/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import Rxn.{ pure, ret, lift, computed }
import Rxn.unsafe.retry

sealed trait RxnLaws {

  // This is to make sure our `Arbitrary` instance
  // only creates deterministic `Rxn`s.
  def equalsItself[A, B](rxn: Rxn[A, B]): IsEq[Rxn[A, B]] = {
    rxn <-> rxn
  }

  def asIsMap[A, B, C](rxn: A =#> B, c: C): IsEq[A =#> C] =
    rxn.as(c) <-> rxn.map[C](_ => c)

  def voidIsMap[A, B](rxn: A =#> B): IsEq[A =#> Unit] =
    rxn.void <-> rxn.map[Unit](_ => ())

  def provideIsContramap[A, B](a: A, rxn: A =#> B): IsEq[Axn[B]] =
    rxn.provide(a) <-> rxn.contramap[Any](_ => a)

  def pureIsRet[A](a: A): IsEq[Axn[A]] =
    pure(a) <-> ret(a)

  def toFunctionIsProvide[A, B](rxn: A =#> B, a: A): IsEq[Axn[B]] =
    rxn.toFunction(a) <-> rxn.provide(a)

  def mapIsAndThenLift[A, B, C](rxn: A =#> B, f: B => C): IsEq[Rxn[A, C]] =
    rxn.map(f) <-> (rxn >>> lift(f))

  def contramapIsLiftAndThen[A, B, C](f: A => B, rxn: B =#> C): IsEq[Rxn[A, C]] =
    rxn.contramap(f) <-> (lift(f) >>> rxn)

  def timesIsAndAlso[A, B, C](x: A =#> B, y: A =#> C): IsEq[A =#> (B, C)] =
    (x * y) <-> (x × y).contramap[A](a => (a, a))

  def andAlsoIsAndThen[A, B, C, D](x: A =#> B, y: C =#> D): IsEq[Rxn[(A, C), (B, D)]] =
    (x × y) <-> (x.first[C] >>> y.second[B])

  def distributiveAndThenChoice1[A, B, C](x: A =#> B, y: B =#> C, z: B =#> C): IsEq[Rxn[A, C]] =
    (x >>> (y + z)) <-> ((x >>> y) + (x >>> z))

  def distributiveAndThenChoice2[A, B, C](x: A =#> B, y: A =#> B, z: B =#> C): IsEq[Rxn[A, C]] =
    ((x + y) >>> z) <-> ((x >>> z) + (y >>> z))

  def distributiveAndAlsoChoice1[A, B, C, D](x: A =#> B, y: C =#> D, z: C =#> D): IsEq[Rxn[(A, C), (B, D)]] =
    (x × (y + z)) <-> ((x × y) + (x × z))

  def distributiveAndAlsoChoice2[A, B, C, D](x: A =#> B, y: A =#> B, z: C =#> D): IsEq[Rxn[(A, C), (B, D)]] =
    ((x + y) × z) <-> ((x × z) + (y × z))

  def associativeAndAlso[A, B, C, D, E, F](x: A =#> B, y: C =#> D, z: E =#> F): IsEq[Rxn[((A, C), E), ((B, D), F)]] = {
    ((x × y) × z) <-> (x × (y × z)).dimap[((A, C), E), ((B, D), F)](
      ac_e => (ac_e._1._1, (ac_e._1._2, ac_e._2))
    )(
      b_df => ((b_df._1, b_df._2._1), b_df._2._2)
    )
  }

  def flatMapFIsAndThenComputed[A, B, C](x: A =#> B, f: B => Axn[C]): IsEq[Rxn[A, C]] =
    x.flatMapF(f) <-> (x >>> computed(f))

  def flatMapIsSecondAndThenComputed[A, B, C](x: A =#> B, f: B => Rxn[A, C]): IsEq[Rxn[A, C]] =
    x.flatMap(f) <-> flatMapDerived(x, f)

  private def flatMapDerived[A, B, C](rxn: Rxn[A, B], f: B => Rxn[A, C]): Rxn[A, C] = {
    val self: Rxn[A, (A, B)] = rxn.second[A].contramap[A](x => (x, x))
    val comp: Rxn[(A, B), C] = computed[(A, B), C](xb => f(xb._2).provide(xb._1))
    self >>> comp
  }

  // TODO: does this always hold?
  def choiceRetryNeutralRight[A, B](x: A =#> B) =
    (x + retry[A, B]) <-> x

  // TODO: does this always hold?
  def choiceRetryNeutralLeft[A, B](x: A =#> B) =
    (retry[A, B] + x) <-> x

  // TODO: do these make a monoid with `+`?
}

object RxnLaws {
  def newRxnLaws: RxnLaws =
    new RxnLaws {}
}