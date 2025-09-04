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
import cats.effect.kernel.Outcome

import dev.tauri.choam.stm.TxnBaseSpecTicked

final class TQueueSpecTicked_Direct_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TQueueSpecTicked_Direct[IO]

final class TQueueSpecTicked_Wrapped_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TQueueSpecTicked_Wrapped[IO]

trait TQueueSpecTicked_Direct[F[_]] extends TQueueSpecTicked[F] { this: McasImplSpec =>

  protected final override def newUnbounded[A]: F[TQueue[A]] =
    TQueue.unboundedDirect[A].commit
}

trait TQueueSpecTicked_Wrapped[F[_]] extends TQueueSpecTicked[F] { this: McasImplSpec =>

  protected final override def newUnbounded[A]: F[TQueue[A]] =
    TQueue.unboundedWrapped[A].commit
}

trait TQueueSpecTicked[F[_]] extends TxnBaseSpecTicked[F] { this: McasImplSpec =>

  protected def newUnbounded[A]: F[TQueue[A]]

  test("TQueue") {
    val t = for {
      q <- newUnbounded[Int]
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
      q <- newUnbounded[Int]
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
      q <- newUnbounded[Int]
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

  test("TQueue ordered wakeup(?)") {
    for {
      q1 <- newUnbounded[Int]
      q2 <- newUnbounded[Int]
      take1 <- (q1.take orElse q2.take).commit.start
      _ <- this.tickAll
      take2 <- (q2.take orElse q1.take).commit.start
      _ <- this.tickAll
      // if suspended transactions are ordered, now
      // `take1` is first at both `q1` and `q2` (and
      // `take2` is second at both)
      _ <- F.both(q2.put(1).commit, q1.put(2).commit)
      // if wakeup works like "notifyOne", then now,
      // after these 2 `put` transactions commit,
      // they will both concurrently try to wake up
      // `take1` (as that's first); so we should make
      // sure that (1) the loser (who couldn't woke
      // up `take1`) must try to wake up another (in
      // this case `take2`), and (2) if the woken up
      // transaction suspends OR (completes without
      // writing into the Ref which woken it up), it
      // must wake up another (doesn't happen in this
      // case)
      v1 <- take1.joinWithNever
      v2 <- take2.joinWithNever
      _ <- assertEqualsF(Set(v1, v2), Set(1, 2))
    } yield ()
  }
}
