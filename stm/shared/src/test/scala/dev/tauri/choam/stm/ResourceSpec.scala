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
package stm

import cats.syntax.all._
import cats.effect.kernel.Async
import cats.effect.{ IO, SyncIO }
import cats.effect.syntax.all._

import munit.CatsEffectSuite
import dev.tauri.choam.stm.Transactive

final class ResourceSpecIO extends ResourceSpec[IO]

abstract class ResourceSpec[F[_]]()(implicit F: Async[F]) extends CatsEffectSuite with BaseSpec {

  test("Transactive.forAsyncRes") {
    Transactive.forAsyncRes[F].use { transactive =>
      transactive.commit(Txn.pure(42)).flatMap { v =>
        F.delay { assertEquals(v, 42) }
      }
    }
  }

  test("Transactive.forAsyncResIn") {
    val (transactive, close) = Transactive.forAsyncResIn[SyncIO, F].allocated.unsafeRunSync()
    transactive.commit(Txn.pure(42)).flatMap { v =>
      F.delay { assertEquals(v, 42) }
    }.guarantee(close.to[F])
  }
}
