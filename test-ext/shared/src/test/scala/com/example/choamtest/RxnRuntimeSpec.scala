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

package com.example.choamtest

import cats.syntax.all._
import cats.effect.kernel.Sync
import cats.effect.{ IO, SyncIO, Resource }

import dev.tauri.choam.{ Reactive, Ref }
import dev.tauri.choam.core.RxnRuntime
import dev.tauri.choam.async.AsyncReactive
import dev.tauri.choam.stm.{ Transactive, TRef }

final class RxnRuntimeSpec extends munit.CatsEffectSuite {

  private def checkReactive[F[_]](implicit r: Reactive[F], F: Sync[F]): F[Ref[Int]] = {
    for {
      ref <- Ref(0).run[F]
      _ <- ref.update(_ + 1).run
      act <- ref.get.run
      _ <- F.delay(assertEquals(act, 1))
    } yield ref
  }

  private def checkTransactive[F[_]](implicit t: Transactive[F], F: Sync[F]): F[TRef[Int]] = {
    for {
      tref <- TRef[Int](0).commit
      _ <- tref.update(_ + 1).commit
      act <- tref.get.commit
      _ <- F.delay(assertEquals(act, 1))
    } yield tref
  }

  private def checkSameRt[A, B](r1: Ref[A], r2: Ref[B]): IO[Unit] = {
    // if they have different runtimes,
    // their hashCode would very likely
    // be the same:
    IO(assertNotEquals(r1.##, r2.##))
  }

  private def checkSameRt[F[_], A, B](r1: Ref[A], r2: TRef[B]): IO[Unit] = {
    // if they have different runtimes,
    // their hashCode would very likely
    // be the same:
    IO(assertNotEquals(r1.##, r2.##))
  }

  test("It should be possible to create a Reactive and Transactive sharing the same runtime (directly)") {
    val res: Resource[IO, (Reactive[IO], Transactive[IO])] = {
      RxnRuntime[IO].flatMap { rt =>
        Reactive.from[IO](rt).flatMap { r =>
          Transactive.from[IO](rt).map { t =>
            (r, t)
          }
        }
      }
    }
    res.use { rt =>
      (checkReactive(rt._1, IO.asyncForIO), checkTransactive(rt._2, IO.asyncForIO)).flatMapN { (r1, r2) =>
        checkSameRt(r1, r2)
      }
    }
  }

  test("It should be possible to create an AsyncReactive and Transactive sharing the same runtime (directly)") {
    val res: Resource[IO, (AsyncReactive[IO], Transactive[IO])] = {
      RxnRuntime[IO].flatMap { rt =>
        AsyncReactive.from[IO](rt).flatMap { r =>
          Transactive.from[IO](rt).map { t =>
            (r, t)
          }
        }
      }
    }
    res.use { rt =>
      (checkReactive(rt._1, IO.asyncForIO), checkTransactive(rt._2, IO.asyncForIO)).flatMapN { (r1, r2) =>
        checkSameRt(r1, r2)
      }
    }
  }

  test("It should be possible to create a Reactive[IO] and a Reactive[SyncIO] sharing the same runtime") {
    val res: Resource[IO, (Reactive[IO], Reactive[SyncIO])] = {
      RxnRuntime[IO].flatMap { rt =>
        Reactive.from[IO](rt).flatMap { rIo =>
          Reactive.fromIn[IO, SyncIO](rt).map { rSyncIo =>
            (rIo, rSyncIo)
          }
        }
      }
    }
    res.use { rr =>
      for {
        r1 <- checkReactive[IO](rr._1, IO.asyncForIO)
        r2 <- checkReactive[SyncIO](rr._2, SyncIO.syncForSyncIO).to[IO]
        _ <- checkSameRt(r1, r2)
      } yield ()
    }
  }
}
