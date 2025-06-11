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
package core

import cats.syntax.all._
import cats.effect.kernel.Async
import cats.effect.{ IO, SyncIO }
import cats.effect.syntax.all._

import munit.CatsEffectSuite

final class ResourceSpecIO extends ResourceSpec[IO]

abstract class ResourceSpec[F[_]]()(implicit F: Async[F]) extends CatsEffectSuite with BaseSpec {

  test("Reactive.forSyncRes") {
    Reactive.forSync[F].use { reactive =>
      reactive(Axn.pure(42)).flatMap { v =>
        F.delay { assertEquals(v, 42) }
      }
    }
  }

  test("Reactive.forSyncResIn") {
    val (reactive, close) = Reactive.forSyncIn[SyncIO, F].allocated.unsafeRunSync()
    reactive(Axn.pure(42)).flatMap { v =>
      F.delay { assertEquals(v, 42) }
    }.guarantee(close.to[F])
  }

  test("AsyncReactive.forAsync") {
    AsyncReactive.forAsync[F].use { reactive =>
      reactive.applyAsync(Axn.pure(42), null).flatMap { v =>
        F.delay { assertEquals(v, 42) }
      }
    }
  }

  test("AsyncReactive.forAsyncIn") {
    val (reactive, close) = AsyncReactive.forAsyncIn[SyncIO, F].allocated.unsafeRunSync()
    reactive.applyAsync(Axn.pure(42), null).flatMap { v =>
      F.delay { assertEquals(v, 42) }
    }.guarantee(close.to[F])
  }

  test("Working on a Ref with 2 different MCAS impls") {
    // This should never happen in normal code!
    // This test is testing that if it happens,
    // there is (likely) at least an exception.
    Reactive.forSync[F].use { reactive1 =>
      Reactive.forSync[F].use { reactive2 =>
        for {
          ref <- reactive1(Ref(42))
          v <- reactive1(ref.updateAndGet(_ + 1))
          _ <- F.delay(assertEquals(v, 43))
          r <- reactive2(ref.updateAndGet(_ + 1)).attempt
          _ <- F.delay(assert(r.isLeft)) // IllegalArgumentException from MutDescriptor
        } yield ()
      }
    }
  }
}
