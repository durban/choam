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

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.IO

final class OrElseRetrySpec_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with OrElseRetrySpec[IO]

trait OrElseRetrySpec[F[_]] extends TxnBaseSpec[F] with TestContextSpec[F] { this: McasImplSpec =>

  private[this] final def log(msg: String): Unit =
    println(msg)

  private def succeedWith[A](name: String, result: A): Txn[F, A] = {
    Txn.unsafe.delay {
      log(s" $name succeeding with $result")
      result
    }
  }

  private def transientFailureOnceThenSucceedWith[A](name: String, result: A): Txn[F, A] = {
    val flag = new AtomicBoolean(true)
    Txn.unsafe.delay { flag.getAndSet(false) }.flatMap { doRetry =>
      if (doRetry) {
        log(s" $name retrying")
        Txn.unsafe.retryUnconditionally
      } else {
        log(s" $name succeeding with $result")
        Txn.pure(result)
      }
    }
  }

  private def succeedIfPositive[A](name: String, ref: TRef[F, Int], result: A): Txn[F, A] = {
    ref.get.flatMap { i =>
      if (i > 0) {
        log(s" $name succeeding with $result")
        Txn.pure(result)
      } else {
        log(s" $name retrying")
        Txn.retry
      }
    }
  }

  // Note: this semantics is probably better for STM,
  // because this way trying `t2` means for sure that
  // `t1` executed a `Txn.retry`.
  test("Txn - `t1 orElse t2`: `t1` transient failure -> retry `t1`".fail) { // TODO: expected failure for now
    log("Txn - `t1 orElse t2`: `t1` transient failure")
    val t1: Txn[F, Int] = transientFailureOnceThenSucceedWith("t1", 1)
    val t2: Txn[F, Int] = succeedWith("t2", 2)
    val txn: Txn[F, Int] = t1 orElse t2
    assertResultF(txn.commit, 1)
  }

  // Note: we NEED this semantics for STM `retry`.
  test("Txn - `t1 orElse t2`: `t1` permanent failure -> try `t2`") {
    log("Txn - `t1 orElse t2`: `t1` permanent failure")
    TRef[F, Int](0).commit.flatMap { ref =>
      val t1: Txn[F, Int] = succeedIfPositive("t1", ref, 1)
      val t2: Txn[F, Int] = succeedWith("t2", 2)
      val txn: Txn[F, Int] = t1 orElse t2
      assertResultF(txn.commit, 2)
    }
  }

  // Note: this semantics is probably better for STM,
  // because see above.
  test("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` transient failure -> retry `t1`".fail) { // TODO: expected failure for now
    log("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` transient failure")
    val t1: Txn[F, Int] = succeedWith("t1", 1)
    val t2: Txn[F, Int] = succeedWith("t2", 2)
    val t3: Txn[F, Int] = transientFailureOnceThenSucceedWith("t3", 3)
    val txn: Txn[F, Int] = (t1 orElse t2) <* t3
    assertResultF(txn.commit, 1)
  }

  // Note: we probably need this semantics because apparently STMs work like this.
  test("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` permanent failure -> retry `t1`") {
    log("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` permanent failure")
    TRef[F, Int](0).commit.flatMap { ref =>
      val t1: Txn[F, Int] = succeedWith("t1", 1)
      val t2: Txn[F, Int] = succeedWith("t2", 2)
      val t3: Txn[F, Int] = succeedIfPositive("t3", ref, 3)
      val txn = (t1 orElse t2) <* t3
      for {
        fib <- txn.commit.start
        _ <- this.tickAll
        _ <- F.delay(log(" setting ref"))
        _ <- ref.set(1).commit
        _ <- this.tickAll
        _ <- assertResultF(fib.joinWithNever, 1)
      } yield ()
    }
  }
}
