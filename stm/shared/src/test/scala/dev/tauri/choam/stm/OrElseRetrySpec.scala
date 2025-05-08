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

import cats.effect.{ IO, Deferred }

final class OrElseRetrySpec_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with OrElseRetrySpec[IO]

trait OrElseRetrySpec[F[_]] extends TxnBaseSpecTicked[F] { this: McasImplSpec =>

  private[this] final def log(msg: String): F[Unit] =
    F.delay { ulog(msg) }

  private[this] final def tlog(msg: String): Txn[Unit] =
    Txn.unsafe.delay { ulog(msg) }

  private[this] final def ulog(msg: String): Unit =
    println(msg)

  private def succeedWith[A](name: String, result: A): Txn[A] = {
    Txn.unsafe.delay {
      ulog(s" $name succeeding with $result")
      result
    }
  }

  private def failWith[A](name: String, ex: Throwable): Txn[A] = {
    Txn.unsafe.delay {
      ulog(s" $name throwing $ex")
      throw ex
    }
  }

  private def transientFailureOnceThenSucceedWith[A](name: String, result: A): Txn[A] = {
    val flag = new AtomicBoolean(true)
    Txn.unsafe.delay { flag.getAndSet(false) }.flatMap { doRetry =>
      if (doRetry) {
        tlog(s" $name retrying") *> Txn.unsafe.retryUnconditionally
      } else {
        tlog(s" $name succeeding with $result") *> Txn.pure(result)
      }
    }
  }

  private def succeedIfPositive[A](name: String, ref: TRef[Int], result: A): Txn[A] = {
    succeedIf(name, ref, result, _ > 0)
  }

  private def succeedIfNegative[A](name: String, ref: TRef[Int], result: A): Txn[A] = {
    succeedIf(name, ref, result, _ < 0)
  }

  private def succeedIf[A](name: String, ref: TRef[Int], result: A, predicate: Int => Boolean): Txn[A] = {
    ref.get.flatMap { i =>
      if (predicate(i)) {
        tlog(s" $name succeeding with $result") *> Txn.pure(result)
      } else {
        tlog(s" $name retrying") *> Txn.retry
      }
    }
  }

  // Note: this semantics is probably better for STM,
  // because this way trying `t2` means for sure that
  // `t1` executed a `Txn.retry`.
  test("Txn - `t1 orElse t2`: `t1` transient failure -> retry `t1`") {
    log("Txn - `t1 orElse t2`: `t1` transient failure") *> {
      val t1: Txn[Int] = transientFailureOnceThenSucceedWith("t1", 1)
      val t2: Txn[Int] = succeedWith("t2", 2)
      val txn: Txn[Int] = t1 orElse t2
      assertResultF(txn.commit, 1)
    }
  }

  // Note: we NEED this semantics for STM `retry`.
  test("Txn - `t1 orElse t2`: `t1` permanent failure -> try `t2`") {
    log("Txn - `t1 orElse t2`: `t1` permanent failure") *> {
      TRef[Int](0).commit.flatMap { ref =>
        val t1: Txn[Int] = succeedIfPositive("t1", ref, 1)
        val t2: Txn[Int] = succeedWith("t2", 2)
        val txn: Txn[Int] = t1 orElse t2
        assertResultF(txn.commit, 2)
      }
    }
  }

  // Note: this semantics is probably better for STM,
  // because see above.
  test("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` transient failure -> retry `t1`") {
    log("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` transient failure") *> {
      val t1: Txn[Int] = succeedWith("t1", 1)
      val t2: Txn[Int] = failWith("t2", new Exception("t2 error"))
      val t3: Txn[Int] = transientFailureOnceThenSucceedWith("t3", 3)
      val txn: Txn[Int] = (t1 orElse t2) <* t3
      assertResultF(txn.commit, 1)
    }
  }

  // Note: we probably need this semantics because apparently STMs work like this.
  test("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` permanent failure -> retry `t1`") {
    log("Txn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` permanent failure") *> {
      TRef[Int](0).commit.flatMap { ref =>
        val t1: Txn[Int] = succeedWith("t1", 1)
        val t2: Txn[Int] = failWith("t2", new Exception("t2 error"))
        val t3: Txn[Int] = succeedIfPositive("t3", ref, 3)
        val txn = (t1 orElse t2) <* t3
        for {
          fib <- txn.commit.start
          _ <- this.tickAll
          _ <- log(" setting ref")
          _ <- ref.set(1).commit
          _ <- this.tickAll
          _ <- assertResultF(fib.joinWithNever, 1)
        } yield ()
      }
    }
  }

  // Combined tests (it is only possible to combine `orElse` and `+` by  using `unsafe`):

  test("Txn - `(t1 orElse t2) + t3`: `t1` transient failure -> try `t3` (NOT `t2`)") {
    log("Txn - `(t1 orElse t2) + t3`: `t1` transient failure") *> {
      val t1: Txn[Int] = transientFailureOnceThenSucceedWith("t1", 1)
      val t2: Txn[Int] = succeedWith("t2", 2)
      val t3: Txn[Int] = succeedWith("t3", 3)
      val txn: Txn[Int] = Txn.unsafe.plus(t1 orElse t2, t3)
      assertResultF(txn.commit, 3)
    }
  }

  test("Txn - `(t1 + t2) orElse (t3 + t4)`") {
    log("Txn - `(t1 + t2) orElse (t3 + t4)`") *> {
      TRef[Int](0).commit.flatMap { ref =>
        val t1: Txn[Int] = transientFailureOnceThenSucceedWith("t1", 1)
        val t2: Txn[Int] = succeedIfPositive("t2", ref, 2)
        val t3: Txn[Int] = transientFailureOnceThenSucceedWith("t3", 3)
        val t4: Txn[Int] = succeedWith("t4", 4)
        val txn: Txn[Int] = Txn.unsafe.plus(t1, t2) orElse Txn.unsafe.plus(t3, t4)
        assertResultF(txn.commit, 4)
      }
    }
  }

  // Race conditions:

  test("Txn - `t1 orElse t2`: `t1` permanent failure; `t2` reads the same ref, but it changed since") {
    log("Txn - race1") *> {
      TRef[Int](0).commit.flatMap { ref =>
        val t1: Txn[Int] = succeedIfPositive("t1", ref, 1)
        val t2: Txn[Int] = succeedIfNegative("t2", ref, 2)
        val txn: Txn[Int] = t1 orElse t2
        for {
          d <- Deferred[F, Unit]
          stepper <- mkStepper
          fib <- stepper.commit(txn).guarantee(d.complete(()).void).start
          _ <- this.tickAll // we're stopping at the `t1` retry
          // another transaction changes `ref`:
          _ <- ref.set(1).commit
          // now try `t2`, which will retry:
          _ <- stepper.stepAndTickAll // we're stopping at the `t2` retry
          _ <- assertResultF(d.tryGet, None)
          // now `txn` tries to suspend with subscribing to `ref`;
          // however, it changed since `t1` have seen it, so `txn`
          // mustn't suspend at all:
          _ <- stepper.stepAndTickAll
          _ <- assertResultF(d.tryGet, Some(()))
          _ <- assertResultF(fib.joinWithNever, 1)
        } yield ()
      }
    }
  }

  test("Txn - consistency of 2 sides of `orElse`") {
    log("Txn - race2") *> {
      TRef[Int](0).commit.flatMap { ref =>
        val t1: Txn[Int] = succeedIfPositive("t1", ref, 1)
        val t2: Txn[Int] = succeedIfPositive("t2", ref, 2)
        val txn: Txn[Int] = t1 orElse t2
        for {
          d <- Deferred[F, Unit]
          stepper <- mkStepper
          fib <- stepper.commit(txn).guarantee(d.complete(()).void).start
          _ <- this.tickAll // we're stopping at the `t1` retry (because it read 0)
          // another transaction changes `ref`:
          _ <- ref.set(1).commit
          // now try `t2`, which MUST read the same 0, and retry:
          _ <- stepper.stepAndTickAll
          _ <- assertResultF(d.tryGet, None)
          // now complete restart, `t1` re-reads, it's 1, so it succeeds:
          _ <- stepper.stepAndTickAll
          _ <- assertResultF(d.tryGet, Some(()))
          _ <- assertResultF(fib.joinWithNever, 1)
        } yield ()
      }
    }
  }
}
