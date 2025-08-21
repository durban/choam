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

import internal.ChoamCatsEffectSuite

final class ResourceSpecIO extends ChoamCatsEffectSuite with ResourceSpec[IO] {

  final override implicit def F: Async[IO] =
    IO.asyncForIO
}

trait ResourceSpec[F[_]] extends BaseSpec {

  implicit def F: Async[F]

  private val crt: ChoamRuntime = ChoamRuntime.unsafeBlocking()

  private val crt2: ChoamRuntime = ChoamRuntime.unsafeBlocking()

  final override def afterAll(): Unit = {
    crt2.unsafeCloseBlocking()
    crt.unsafeCloseBlocking()
    super.afterAll()
  }

  test("Reactive.from") {
    Reactive.from[F](crt).use { reactive =>
      reactive.run(Rxn.pure(42)).flatMap { v =>
        F.delay { assertEquals(v, 42) }
      }
    }
  }

  test("Reactive.fromIn") {
    val (reactive, close) = Reactive.fromIn[SyncIO, F](crt).allocated.unsafeRunSync()
    reactive.run(Rxn.pure(42)).flatMap { v =>
      F.delay { assertEquals(v, 42) }
    }.guarantee(close.to[F])
  }

  test("AsyncReactive.from") {
    AsyncReactive.from[F](crt).use { reactive =>
      reactive.runAsync(Rxn.pure(42)).flatMap { v =>
        F.delay { assertEquals(v, 42) }
      }
    }
  }

  test("AsyncReactive.fromIn") {
    val (reactive, close) = AsyncReactive.fromIn[SyncIO, F](crt).allocated.unsafeRunSync()
    reactive.runAsync(Rxn.pure(42)).flatMap { v =>
      F.delay { assertEquals(v, 42) }
    }.guarantee(close.to[F])
  }

  test("Working on a Ref with 2 different MCAS impls") {
    // This should never happen in normal code!
    // This test is testing that if it happens,
    // there is (likely) at least an exception.
    Reactive.from[F](crt).use { reactive1 =>
      Reactive.from[F](crt2).use { reactive2 =>
        for {
          ref <- reactive1.run(Ref(42))
          v <- reactive1.run(ref.updateAndGet(_ + 1))
          _ <- F.delay(assertEquals(v, 43))
          r <- reactive2.run(ref.updateAndGet(_ + 1)).attempt
          _ <- F.delay(assert(r.isLeft)) // IllegalArgumentException from MutDescriptor
        } yield ()
      }
    }
  }
}
