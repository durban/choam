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

  // Note: we NEED this semantics for elimination.
  test("Rxn - `t1 + t2`: `t1` transient failure -> try `t2`") {
    log("Rxn - `t1 + t2`: `t1` transient failure") *> {
      val t1: Axn[Int] = retryOnceThenSucceedWith("t1", 1)
      val t2: Axn[Int] = succeedWith("t2", 2)
      val rxn: Axn[Int] = t1 + t2
      assertResultF(rxn.run, 2)
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
}
