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
  extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("applyAsync") {
    val r: Rxn[Int] = Rxn.pure(3)
    val never: Rxn[Int] = Rxn.unsafe.retry
    val sSpin: RetryStrategy.CanSuspend[false] = RetryStrategy.spin(
      maxRetries = Some(128),
      maxSpin = 512,
      randomizeSpin = true,
    )
    val sCede: RetryStrategy.CanSuspend[true] = RetryStrategy.cede(
      maxRetries = Some(128),
      maxSpin = 512,
      randomizeSpin = true,
      maxCede = 1,
      randomizeCede = false,
    )
    val sSleep: RetryStrategy.CanSuspend[true] = RetryStrategy.sleep(
      maxRetries = Some(128),
      maxSpin = 512,
      randomizeSpin = true,
      maxCede = 1,
      randomizeCede = false,
      maxSleep = 1.millis,
      randomizeSleep = false,
    )
    for {
      _ <- assertResultF(AsyncReactive[F].runAsync(r, sSpin), 3)
      _ <- assertResultF(AsyncReactive[F].runAsync(r, sCede), 3)
      _ <- assertResultF(AsyncReactive[F].runAsync(r, sSleep), 3)
      _ <- assertRaisesF(AsyncReactive[F].runAsync(never, sSpin), _.isInstanceOf[Rxn.MaxRetriesExceeded])
      _ <- assertRaisesF(AsyncReactive[F].runAsync(never, sCede), _.isInstanceOf[Rxn.MaxRetriesExceeded])
      _ <- assertRaisesF(AsyncReactive[F].runAsync(never, sSleep), _.isInstanceOf[Rxn.MaxRetriesExceeded])
    } yield ()
  }

  test("Exception passthrough (AsyncReactive)") {
    (Ref(0) * Ref.array(4, 0) * Rxn.unsafe.newLocal(0)).run[F].flatMap { case ((ref, arr), local) =>
      throwingRxns(ref, arr, local).traverse_[F, Unit] { r =>
        AsyncReactive[F].runAsync(r, RetryStrategy.Default).attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
        AsyncReactive[F].runAsync(r, RetryStrategy.Default.withCede).attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
        AsyncReactive[F].runAsync(r, RetryStrategy.Default.withSleep).attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
      }
    }
  }

  test("MaxRetriesExceeded exception handling") {
    val str = RetryStrategy.Default.withMaxRetries(Some(1))
    val throwingRxn = Rxn.unsafe.retry[Int]
    for {
      // simple:
      _ <- AsyncReactive[F].runAsync(
        throwingRxn,
        str,
      ).attemptNarrow[Rxn.MaxRetriesExceeded].flatMap(e => assertF(e.isLeft))
      // in post-commit action:
      pcRef1 <- Ref(0).run
      pcRef2 <- Ref(0).run
      _ <- AsyncReactive[F].runAsync(
        Rxn.pure(42).postCommit(pcRef1.update(_ + 1) *> throwingRxn.void).postCommit(pcRef2.update(_ + 1)),
        str,
      ).attemptNarrow[Rxn.PostCommitException].flatMap[Unit] {
        case Left(ex) =>
          assertEqualsF(ex.committedResult, 42) *> assertEqualsF(ex.errors.size, 1) *> assertF(
            ex.errors.head.isInstanceOf[Rxn.MaxRetriesExceeded]
          )
        case Right(r) =>
          failF(s"unexpected success: ${r}")
      }
      _ <- assertResultF(pcRef1.get.run, 0)
      _ <- assertResultF(pcRef2.get.run, 1)
      // in nested post-commit action:
      pcRef3 <- Ref(0).run
      pcRef4 <- Ref(0).run
      _ <- AsyncReactive[F].runAsync(
        Rxn.pure(42).postCommit(pcRef3.update(_ + 1).postCommit(throwingRxn.void).postCommit(pcRef4.update(_ + 1))),
        str,
      ).attemptNarrow[Rxn.PostCommitException].flatMap[Unit] {
        case Left(ex) =>
          assertEqualsF(ex.committedResult, 42) *> assertEqualsF(ex.errors.size, 1) *> assertF(
            ex.errors.head.isInstanceOf[Rxn.MaxRetriesExceeded]
          )
        case Right(r) =>
          failF(s"unexpected success: ${r}")
      }
      _ <- assertResultF(pcRef3.get.run, 1)
      _ <- assertResultF(pcRef4.get.run, 1)
    } yield ()
  }
}
