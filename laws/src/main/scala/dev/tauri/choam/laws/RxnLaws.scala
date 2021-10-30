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

import cats.laws.IsEq
import cats.laws.IsEqArrow

trait RxnLaws {

  def asIsMap[A, B, C](rxn: A =#> B, c: C): IsEq[A =#> C] =
    rxn.as(c) <-> rxn.map[C](_ => c)

  def voidIsMap[A, B](rxn: A =#> B): IsEq[A =#> Unit] =
    rxn.void <-> rxn.map[Unit](_ => ())

  def provideIsContramap[A, B](a: A, rxn: A =#> B): IsEq[Axn[B]] =
    rxn.provide(a) <-> rxn.contramap[Any](_ => a)

  def pureIsRet[A](a: A): IsEq[Axn[A]] =
    Rxn.pure(a) <-> Rxn.ret(a)
}
