/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.{ IO, ConcurrentEffect, ContextShift, Timer }

import munit.{ CatsEffectSuite, Location, FunSuite }

trait BaseSpecF[F[_]]
  extends FunSuite
  with cats.syntax.AllSyntax
  with cats.effect.syntax.AllCatsEffectSyntax {

  // TODO: use `Concurrent` with ce3
  implicit def F: ConcurrentEffect[F]

  implicit def csF: ContextShift[F]

  implicit def tmF: Timer[F]

  def assertF(cond: Boolean, clue: String = "assertion failed")(implicit loc: Location): F[Unit] = {
    F.delay { this.assert(cond, clue) }
  }

  def assertEqualsF[A, B](obtained: A, expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit] = {
    F.delay { this.assertEquals[A, B](obtained, expected, clue) }
  }

  def assertResultF[A, B](obtained: F[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit]
}

abstract class IOSpecMUnit extends CatsEffectSuite with BaseSpecF[IO] {

  implicit final override def F: ConcurrentEffect[IO] =
    IO.ioConcurrentEffect(munitContextShift)

  final override def csF: ContextShift[IO] =
    munitContextShift

  final override def tmF: Timer[IO] =
    munitTimer

  final override def assertResultF[A, B](obtained: IO[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): IO[Unit] = {
    assertIO(obtained, expected, clue)
  }
}
