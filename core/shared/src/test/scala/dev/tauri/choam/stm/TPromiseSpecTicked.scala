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

import cats.effect.kernel.{ Ref => CatsRef }
import cats.effect.IO

final class TPromiseSpecTicked_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TPromiseSpecTicked[IO]

trait TPromiseSpecTicked[F[_]] extends TxnBaseSpecTicked[F] { this: McasImplSpec =>

  test("get after complete") {
    for {
      p <- TPromise[Int].commit
      _ <- assertResultF(p.complete(42).commit, true)
      _ <- assertResultF(p.complete(99).commit, false)
      _ <- assertResultF(p.get.commit, 42)
      _ <- assertResultF(p.get.commit, 42)
      _ <- assertResultF(p.tryGet.commit, Some(42))
      _ <- assertResultF(p.tryGet.commit, Some(42))
    } yield ()
  }

  test("get before complete") {
    val t = for {
      p <- TPromise[Int].commit
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
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("complete left side of orElse") {
    val t = for {
      p1 <- TPromise[Int].commit
      p2 <- TPromise[Int].commit
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
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("complete right side of orElse") {
    val t = for {
      p1 <- TPromise[Int].commit
      p2 <- TPromise[Int].commit
      fib <- (p1.get orElse p2.get).commit.start
      _ <- this.tickAll
      _ <- assertResultF(p1.tryGet.commit, None)
      _ <- assertResultF(p2.tryGet.commit, None)
      _ <- assertResultF(p2.complete(99).commit, true)
      _ <- this.tickAll
      _ <- assertResultF(p1.tryGet.commit, None)
      _ <- assertResultF(p2.tryGet.commit, Some(99))
      _ <- assertResultF(fib.joinWithNever, 99)
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("multiple readers") {
    def writeIntoCatsRef(p: TPromise[Int], ref: CatsRef[F, Int]): F[Unit] = {
      p.get.commit.flatMap(ref.set)
    }
    val t = for {
      p <- TPromise[Int].commit
      r1 <- CatsRef.of(0)
      r2 <- CatsRef.of(0)
      r3 <- CatsRef.of(0)
      fib1 <- writeIntoCatsRef(p, r1).start
      fib2 <- writeIntoCatsRef(p, r2).start
      fib3 <- writeIntoCatsRef(p, r3).start
      _ <- this.tickAll
      _ <- assertResultF(r1.get, 0)
      _ <- assertResultF(r2.get, 0)
      _ <- assertResultF(r3.get, 0)
      _ <- assertResultF(p.complete(42).commit, true)
      _ <- this.tickAll
      _ <- assertResultF(r1.get, 42)
      _ <- assertResultF(r2.get, 42)
      _ <- assertResultF(r3.get, 42)
      _ <- fib1.joinWithNever
      _ <- fib2.joinWithNever
      _ <- fib3.joinWithNever
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }
}
