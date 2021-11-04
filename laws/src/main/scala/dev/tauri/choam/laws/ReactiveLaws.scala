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

import cats.Monad
import cats.laws.IsEq
import cats.laws.IsEqArrow
import cats.syntax.all._

trait ReactiveLaws[F[_]] {

  implicit def reactive: Reactive[F]

  implicit def monad: Monad[F] =
    reactive.monad

  def runPure[A](a: A): IsEq[F[A]] =
    reactive.run(Rxn.pure(a), ()) <-> monad.pure(a)

  def runLift[A, B](f: A => B, a: A): IsEq[F[B]] =
    reactive.run(Rxn.lift(f), a) <-> monad.pure(a).map(f)

  def runToFunction[A, B](rxn: A =#> B, a: A): IsEq[F[B]] =
    reactive.run(rxn.toFunction(a), ()) <-> reactive.run(rxn, a)
}

object ReactiveLaws {
  def apply[F[_]](implicit rF: Reactive[F]): ReactiveLaws[F] = new ReactiveLaws[F] {
    implicit override def reactive: Reactive[F] = rF
  }
}