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

import cats.effect._

import munit.{ CatsEffectSuite, Location, FunSuite }

trait MUnitUtils { this: FunSuite =>

  def assertSameInstance[A](
    obtained: A,
    expected: A,
    clue: String = "objects are not the same instance"
  )(implicit loc: Location): Unit = {
    assert(equ(this.clue(obtained), this.clue(expected)), clue)
  }
}

trait BaseSpecA
  extends FunSuite
  with MUnitUtils

trait BaseSpecF[F[_]]
  extends FunSuite
  with MUnitUtils
  with cats.syntax.AllSyntax
  with cats.effect.syntax.AllCatsEffectSyntax {

  implicit def F: Sync[F]

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

  def failF[A](clue: String = "assertion failed")(implicit loc: Location): F[A] = {
    assertF(false, clue).flatMap { _ =>
      F.raiseError[A](new IllegalStateException("unreachable code"))
    }
  }
}

trait BaseSpecAsyncF[F[_]] extends BaseSpecF[F] {
  implicit override def F: ConcurrentEffect[F]
  implicit def csF: ContextShift[F]
  implicit def tmF: Timer[F]
}

trait BaseSpecSyncF[F[_]] extends BaseSpecF[F] {
  implicit override def F: SyncEffect[F]
}

abstract class IOSpecMUnit extends CatsEffectSuite with BaseSpecAsyncF[IO] {

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

abstract class SyncIOSpecMUnit extends CatsEffectSuite with BaseSpecSyncF[SyncIO] {

  implicit final override def F: SyncEffect[SyncIO] =
    SyncIO.syncIOsyncEffect

  final override def assertResultF[A, B](obtained: SyncIO[A], expected: B, clue: String)(
    implicit loc: Location, ev: B <:< A
  ): SyncIO[Unit] = {
    assertSyncIO(obtained, expected, clue)
  }
}
