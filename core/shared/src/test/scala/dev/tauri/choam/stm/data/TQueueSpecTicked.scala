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
package data

import cats.effect.IO
import cats.effect.kernel.Outcome

final class TQueueSpecTicked_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TQueueSpecTicked[IO]

trait TQueueSpecTicked[F[_]] extends TxnBaseSpecTicked[F] { this: McasImplSpec =>

  test("TQueue") {
    val t = for {
      q <- TQueue.unbounded[Int].commit
      take1 <- q.take.commit.start
      take2 <- q.take.commit.start
      _ <- q.put(1).commit
      _ <- q.put(2).commit
      r1 <- take1.joinWithNever
      r2 <- take2.joinWithNever
      _ <- assertEqualsF(Set(r1, r2), Set(1, 2))
      _ <- (q.put(3) *> q.put(4)).commit
      _ <- assertResultF(q.take.commit, 3)
      _ <- assertResultF(q.take.commit, 4)
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("TQueue cancel take") {
    val t = for {
      q <- TQueue.unbounded[Int].commit
      take1 <- q.take.commit.start
      take2 <- q.take.commit.start
      _ <- this.tickAll
      _ <- take1.cancel
      _ <- q.put(1).commit
      _ <- q.put(2).commit
      r1 <- take1.join
      r2 <- take2.joinWithNever
      _ <- assertEqualsF(r2, 1)
      _ <- assertF(r1.isCanceled)
      _ <- (q.put(3) *> q.put(4)).commit
      _ <- assertResultF(q.take.commit, 2)
      _ <- assertResultF(q.take.commit, 3)
      _ <- assertResultF(q.take.commit, 4)
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("TQueue cancel take + put race") {
    val t = for {
      q <- TQueue.unbounded[Int].commit
      take1 <- q.take.commit.start
      take2 <- q.take.commit.start
      _ <- this.tickAll
      _ <- F.both(take1.cancel, q.put(1).commit)
      _ <- q.put(2).commit
      r1 <- take1.join
      r2 <- take2.joinWithNever
      _ <- r1 match {
        case Outcome.Canceled() =>
          assertEqualsF(r2, 1) *> assertResultF(q.take.commit, 2)
        case Outcome.Errored(err) =>
          F.raiseError(err)
        case Outcome.Succeeded(fa) =>
          fa.flatMap { r1 =>
            assertEqualsF(Set(r1, r2), Set(1, 2))
          }
      }
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }
}
