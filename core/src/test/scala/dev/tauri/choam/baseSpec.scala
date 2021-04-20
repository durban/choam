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

import cats.effect.{ Sync, Async, IO, SyncIO, MonadCancel, Temporal }

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
  with cats.effect.syntax.AllSyntax { this: KCASImplSpec =>

  implicit def rF: Reactive[F]

  implicit def mcF: MonadCancel[F, Throwable] =
    this.F

  /** Not implicit, so that `rF` is used for sure */
  def F: Sync[F]

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
    F.flatMap(assertF(false, clue)) { _ =>
      F.raiseError[A](new IllegalStateException("unreachable code"))
    }
  }
}

trait BaseSpecAsyncF[F[_]] extends BaseSpecF[F] { this: KCASImplSpec =>
  /** Not implicit, so that `rF` is used for sure */
  override def F: Async[F]
  override implicit def mcF: Temporal[F] =
    this.F
  override implicit def rF: Reactive.Async[F] =
    new Reactive.AsyncReactive[F](this.kcasImpl)(this.F)
}

trait BaseSpecSyncF[F[_]] extends BaseSpecF[F] { this: KCASImplSpec =>
  /** Not implicit, so that `rF` is used for sure */
  override def F: Sync[F]
  override implicit def rF: Reactive[F] =
    new Reactive.SyncReactive[F](this.kcasImpl)(F)
}

abstract class BaseSpecIO extends CatsEffectSuite with BaseSpecAsyncF[IO] { this: KCASImplSpec =>

  /** Not implicit, so that `rF` is used for sure */
  final override def F: Async[IO] =
    IO.asyncForIO

  final override def assertResultF[A, B](obtained: IO[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): IO[Unit] = {
    assertIO(obtained, expected, clue)
  }
}

abstract class BaseSpecSyncIO extends CatsEffectSuite with BaseSpecSyncF[SyncIO] { this: KCASImplSpec =>

  /** Not implicit, so that `rF` is used for sure */
  final override def F: Sync[SyncIO] =
    SyncIO.syncForSyncIO

  final override def assertResultF[A, B](obtained: SyncIO[A], expected: B, clue: String)(
    implicit loc: Location, ev: B <:< A
  ): SyncIO[Unit] = {
    assertSyncIO(obtained, expected, clue)
  }
}
