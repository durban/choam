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
package data
package stm

import scala.collection.immutable.Set

import cats.effect.IO

import dev.tauri.choam.stm.TxnBaseSpecTicked

import TQueue.WQueue

final class TRefWrapSpec_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TRefWrapSpec[IO]

trait TRefWrapSpec[F[_]] extends TxnBaseSpecTicked[F] { this: McasImplSpec =>

  test("Wrapping a Queue.unbounded with AllocationStrategy.withStm(true)") {
    val t = for {
      q <- WQueue.unbounded[Int].commit
      _ <- q.put(1).commit
      _ <- assertResultF(q.take.commit, 1)
      fib <- q.take.commit.start
      _ <- this.tickAll
      _ <- q.put(2).commit
      _ <- assertResultF(fib.joinWithNever, 2)
      fib1 <- q.take.commit.start
      fib2 <- q.take.commit.start
      _ <- (q.put(3) *> q.put(4)).commit
      v1 <- fib1.joinWithNever
      v2 <- fib2.joinWithNever
      _ <- assertEqualsF(Set(v1, v2), Set(3, 4))
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("Wrapping a Queue.bounded with AllocationStrategy.withStm(true)") {
    val t = for {
      q <- WQueue.bounded[Int](4).commit
      _ <- q.put(1).commit
      _ <- assertResultF(q.take.commit, 1)
      fib <- q.take.commit.start
      _ <- this.tickAll
      _ <- q.put(2).commit
      _ <- assertResultF(fib.joinWithNever, 2)
      fib1 <- q.take.commit.start
      fib2 <- q.take.commit.start
      _ <- (q.put(3) *> q.put(4)).commit
      v1 <- fib1.joinWithNever
      v2 <- fib2.joinWithNever
      _ <- assertEqualsF(Set(v1, v2), Set(3, 4))
      _ <- (q.put(5) *> q.put(6)).commit
      _ <- (q.put(7) *> q.put(8)).commit
      putFib1 <- q.put(9).commit.start
      putFib2 <- q.put(10).commit.start
      _ <- assertResultF(q.take.commit, 5)
      _ <- assertResultF(q.take.commit, 6)
      _ <- putFib1.joinWithNever
      _ <- putFib2.joinWithNever
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }
}

object TRefWrapSpec {


}
