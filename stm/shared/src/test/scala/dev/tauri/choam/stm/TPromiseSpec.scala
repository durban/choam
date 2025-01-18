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

import cats.effect.IO

final class TPromiseSpecTicked_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with TPromiseSpecTicked[IO]

trait TPromiseSpecTicked[F[_]] extends TxnBaseSpec[F] with TestContextSpec[F] { this: McasImplSpec =>

  test("get after complete") {
    for {
      p <- TPromise[F, Int].commit
      _ <- assertResultF(p.complete(42).commit, true)
      _ <- assertResultF(p.complete(99).commit, false)
      _ <- assertResultF(p.get.commit, 42)
      _ <- assertResultF(p.get.commit, 42)
      _ <- assertResultF(p.tryGet.commit, Some(42))
      _ <- assertResultF(p.tryGet.commit, Some(42))
    } yield ()
  }

  test("get before complete") {
    for {
      p <- TPromise[F, Int].commit
      fib <- p.get.commit.start
      _ <- this.tickAll
      _ <- assertResultF(p.tryGet.commit, None)
      _ <- assertResultF(p.complete(42).commit, true)
      _ <- this.tickAll
      _ <- assertResultF(p.tryGet.commit, Some(42))
      _ <- assertResultF(fib.joinWithNever, 42)
      _ <- assertResultF(p.tryGet.commit, Some(42))
      _ <- assertResultF(p.get.commit, 42)
    } yield ()
  }

  test("get on left side of orElse".fail) { // TODO: expected failure
    for {
      p1 <- TPromise[F, Int].commit
      p2 <- TPromise[F, Int].commit
      fib <- (p1.get orElse p2.get).commit.start
      _ <- this.tickAll
      _ <- assertResultF(p1.tryGet.commit, None)
      _ <- assertResultF(p2.tryGet.commit, None)
      _ <- assertResultF(p1.complete(42).commit, true)
      _ <- this.tickAll
      _ <- assertResultF(p1.tryGet.commit, Some(42))
      _ <- assertResultF(p2.tryGet.commit, None)
      _ <- assertResultF(fib.joinWithNever, 42)
    } yield ()
  }
}
