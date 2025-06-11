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

import scala.concurrent.duration._

import cats.effect.IO

import RxnSpec.{ MyException, throwingRxns }

final class AsyncRxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with AsyncRxnSpec[IO]

trait AsyncRxnSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec =>

  test("applyAsync") {
    val r: Rxn[String, Int] =
      Rxn.lift(s => s.length)
    val never: Rxn[String, Int] =
      Rxn.unsafe.retry
    val sSpin = RetryStrategy.spin(
      maxRetries = Some(128),
      maxSpin = 512,
      randomizeSpin = true,
    )
    val sCede = RetryStrategy.cede(
      maxRetries = Some(128),
      maxSpin = 512,
      randomizeSpin = true,
      maxCede = 1,
      randomizeCede = false,
    )
    val sSleep = RetryStrategy.sleep(
      maxRetries = Some(128),
      maxSpin = 512,
      randomizeSpin = true,
      maxCede = 1,
      randomizeCede = false,
      maxSleep = 1.millis,
      randomizeSleep = false,
    )
    for {
      _ <- assertResultF(AsyncReactive[F].applyAsync(r, "foo", sSpin), 3)
      _ <- assertResultF(AsyncReactive[F].applyAsync(r, "foo", sCede), 3)
      _ <- assertResultF(AsyncReactive[F].applyAsync(r, "foo", sSleep), 3)
      _ <- assertRaisesF(AsyncReactive[F].applyAsync(never, "foo", sSpin), _.isInstanceOf[Rxn.MaxRetriesReached])
      _ <- assertRaisesF(AsyncReactive[F].applyAsync(never, "foo", sCede), _.isInstanceOf[Rxn.MaxRetriesReached])
      _ <- assertRaisesF(AsyncReactive[F].applyAsync(never, "foo", sSleep), _.isInstanceOf[Rxn.MaxRetriesReached])
    } yield ()
  }

  test("Exception passthrough (AsyncReactive)") {
    throwingRxns.traverse_[F, Unit] { r =>
      AsyncReactive[F].applyAsync(r, null, RetryStrategy.Default).attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
      AsyncReactive[F].applyAsync(r, null, RetryStrategy.Default.withCede(true)).attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
      AsyncReactive[F].applyAsync(r, null, RetryStrategy.Default.withSleep(true)).attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
    }
  }
}
