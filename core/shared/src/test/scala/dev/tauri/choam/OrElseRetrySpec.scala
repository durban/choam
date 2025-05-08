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

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.IO

final class OrElseRetrySpec_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with OrElseRetrySpec[IO]

trait OrElseRetrySpec[F[_]] extends BaseSpecAsyncF[F] with TestContextSpec[F] { this: McasImplSpec =>

  private[this] final def ulog(msg: String): Unit =
    println(msg)

  private[this] final def log(msg: String): F[Unit] =
    F.delay { ulog(msg) }

  private[this] final def rlog(msg: String): Axn[Unit] =
    Axn.unsafe.delay { ulog(msg) }


  private def succeedWith[A](name: String, result: A): Axn[A] = {
    Axn.unsafe.delay {
      ulog(s" $name succeeding with $result")
      result
    }
  }

  private def failWith[A](name: String, ex: Throwable): Axn[A] = {
    Axn.unsafe.delay {
      ulog(s" $name throwing $ex")
      throw ex
    }
  }

  private def retryOnceThenSucceedWith[A](name: String, result: A): Axn[A] = {
    val flag = new AtomicBoolean(true)
    Axn.unsafe.delay { flag.getAndSet(false) }.flatMapF { doRetry =>
      if (doRetry) {
        // we "simulate" a transient failure
        // with an unconditional retry here
        // (note: for Rxn, we don't have
        // permanent failures)
        rlog(s" $name retrying") *> Rxn.unsafe.retry
      } else {
        rlog(s" $name succeeding with $result") *> Axn.pure(result)
      }
    }
  }

  private def succeedIfPositive[A](name: String, ref: Ref[Int], result: A): Axn[A] = {
    succeedIf(name, ref, result, _ > 0)
  }

  private def succeedIf[A](name: String, ref: Ref[Int], result: A, predicate: Int => Boolean): Axn[A] = {
    ref.get.flatMapF { i =>
      if (predicate(i)) {
        rlog(s" $name succeeding with $result") *> Axn.pure(result)
      } else {
        rlog(s" $name retrying") *> Rxn.unsafe.retryWhenChanged
      }
    }
  }

  // Note: we NEED this semantics for elimination.
  test("Rxn - `t1 + t2`: `t1` transient failure -> try `t2`") {
    log("Rxn - `t1 + t2`: `t1` transient failure") *> {
      val t1: Axn[Int] = retryOnceThenSucceedWith("t1", 1)
      val t2: Axn[Int] = succeedWith("t2", 2)
      val rxn: Axn[Int] = t1 + t2
      assertResultF(rxn.run, 2)
    }
  }

  // Note: this is primarily for STM, but also
  // available through `Rxn.unsafe.orElse`.
  test("Rxn - `t1 orElse t2`: `t1` transient failure -> retry `t1`") {
    log("Rxn - `t1 orElse t2`: `t1` transient failure") *> {
      val t1: Axn[Int] = retryOnceThenSucceedWith("t1", 1)
      val t2: Axn[Int] = succeedWith("t2", 2)
      val rxn: Axn[Int] = Rxn.unsafe.orElse(t1, t2)
      assertResultF(rxn.run, 1)
    }
  }

  // Note: this is primarily for STM, but also
  // available through `Rxn.unsafe.orElse` and
  // `Rxn.unsafe.retryWhenChanged`.
  test("Rxn - `t1 orElse t2`: `t1` permanent failure -> try `t2`") {
    log("Rxn - `t1 orElse t2`: `t1` permanent failure") *> {
      Ref[Int](0).run[F].flatMap { ref =>
        val t1: Axn[Int] = succeedIfPositive("t1", ref, 1)
        val t2: Axn[Int] = succeedWith("t2", 2)
        val rxn: Axn[Int] = Rxn.unsafe.orElse(t1, t2)
        assertResultF(rxn.run, 2)
      }
    }
  }

  // Note: we NEED this semantics for elimination.
  test("Rxn - `(t1 + t2) <* t3`: `t1` succeeds, `t3` transient failure -> try `t2`") {
    log("Rxn - `(t1 + t2) <* t3`: `t1` succeeds, `t3` transient failure") *> {
      val t1: Axn[Int] = succeedWith("t1", 1)
      val t2: Axn[Int] = succeedWith("t2", 2)
      val t3: Axn[Int] = retryOnceThenSucceedWith("t3", 3)
      val rxn: Axn[Int] = (t1 + t2) <* t3
      assertResultF(rxn.run, 2)
    }
  }

  // Note: this is primarily for STM, but also
  // available through `Rxn.unsafe.orElse`.
  test("Rxn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` transient failure -> retry `t1`") {
    log("Rxn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` transient failure") *> {
      val t1: Axn[Int] = succeedWith("t1", 1)
      val t2: Axn[Int] = failWith("t2", new Exception("t2 error"))
      val t3: Axn[Int] = retryOnceThenSucceedWith("t3", 3)
      val rxn: Axn[Int] = Rxn.unsafe.orElse(t1, t2) <* t3
      assertResultF(rxn.run, 1)
    }
  }

  // Note: this is skipped for `Rxn`, because it can't work.
  test("Rxn - `(t1 orElse t2) <* t3`: `t1` succeeds, `t3` permanent failure -> can't work") {
    assume(false)
  }

  // Combined tests (it is only possible to combine `+` and `orElse` by  using `unsafe`):

  test("Rxn - `(t1 orElse t2) + t3`: `t1` transient failure -> try `t3` (NOT `t2`)") {
    log("Rxn - `(t1 orElse t2) + t3`: `t1` transient failure") *> {
      val t1: Axn[Int] = retryOnceThenSucceedWith("t1", 1)
      val t2: Axn[Int] = succeedWith("t2", 2)
      val t3: Axn[Int] = succeedWith("t3", 3)
      val rxn: Axn[Int] = Rxn.unsafe.orElse(t1, t2) + t3
      assertResultF(rxn.run, 3)
    }
  }

  test("Rxn - `(t1 + t2) orElse (t3 + t4)`") {
    log("Rxn - `(t1 + t2) orElse (t3 + t4)`") *> {
      Ref[Int](0).run[F].flatMap { ref =>
        val t1: Axn[Int] = retryOnceThenSucceedWith("t1", 1)
        val t2: Axn[Int] = succeedIfPositive("t2", ref, 2)
        val t3: Axn[Int] = retryOnceThenSucceedWith("t3", 3)
        val t4: Axn[Int] = succeedWith("t4", 4)
        val rxn: Axn[Int] = Rxn.unsafe.orElse(t1 + t2, t3 + t4)
        assertResultF(rxn.run, 4)
      }
    }
  }

  // TODO: also port this:
  // TODO: test("Txn - `t1 orElse t2`: `t1` permanent failure; `t2` reads the same ref, but it changed since")

  // Note: this is primarily for STM, but also
  // available through `Rxn.unsafe.orElse` and
  // `Rxn.unsafe.retryWhenChanged`.
  test("Rxn - consistency of 2 sides of `orElse`") {
    log("Rxn - race2") *> {
      Ref[Int](0).run[F].flatMap { ref =>
        val t1: Axn[Int] = succeedIfPositive("t1", ref, 1)
        val t2: Axn[Int] = succeedIfPositive("t2", ref, 2)
        val t3: Axn[Int] = succeedWith("t3", 3)
        val rxn: Axn[Int] = Rxn.unsafe.orElse(Rxn.unsafe.orElse(t1, t2), t3)
        for {
          d <- F.deferred[Unit]
          stepper <- mkStepper
          fib <- stepper.run(rxn, ()).guarantee(d.complete(()).void).start
          _ <- this.tickAll // we're stopping at the `t1` retry (because it read 0)
          // another transaction changes `ref`:
          _ <- ref.set0[F](1)
          // now try `t2`, which MUST read the same 0, and retry:
          _ <- stepper.stepAndTickAll
          _ <- assertResultF(d.tryGet, None)
          // now try `t3`, which succeeds:
          _ <- stepper.stepAndTickAll
          _ <- assertResultF(d.tryGet, Some(()))
          _ <- assertResultF(fib.joinWithNever, 3)
        } yield ()
      }
    }
  }
}
