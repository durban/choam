/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.kernel.{ Fiber, Outcome }
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LLL_Result

@JCStressTest
@State
@Description("async register/complete/cancel race")
@Outcomes(Array(
  new JOutcome(id = Array("foo, Succeeded(IO(foo)), ()"), expect = ACCEPTABLE_INTERESTING, desc = "succeeded"),
  new JOutcome(id = Array("null, Canceled(), ()"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled early"),
  new JOutcome(id = Array("foo, Canceled(), ()"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled late"), // FORBIDDEN?
))
class CatsAsyncTest {

  private[this] val runtime: IORuntime = {
    val (wstp, fin) = IORuntime.createWorkStealingComputeThreadPool(threads = 3)
    IORuntime.builder().setCompute(wstp, fin).build()
  }

  @volatile
  private[this] var cb: (Either[Throwable, String] => Unit) =
    null

  @volatile
  private[this] var fib: Fiber[IO, Throwable, String] =
    null

  private[this] val res: Either[Throwable, String] =
    Right("foo")

  @Actor
  def register(r: LLL_Result): Unit = {
    val a: IO[String] = IO.uncancelable { poll =>
      poll(
        IO.async[String] { cb =>
          IO { this.cb = cb }.as(Some(IO.unit))
        }
      ).flatTap { s =>
        IO { r.r1 = s }
      }
    }

    val t: IO[Outcome[IO, Throwable, String]] = a.start.flatMap { fib =>
      IO { this.fib = fib } *> fib.join
    }

    r.r2 = t.unsafeRunSync()(this.runtime).toString()
  }

  @Actor
  def complete(): Unit = {
    var cb: (Either[Throwable, String] => Unit) = null
    while (cb eq null) {
      cb = this.cb
    }
    cb(this.res)
  }

  @Actor
  def cancel(r: LLL_Result): Unit = {
    var fib: Fiber[IO, Throwable, String] = null
    while (fib eq null) {
      fib = this.fib
    }
    r.r3 = fib.cancel.unsafeRunSync()(this.runtime)
  }
}
