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

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import cats.effect.kernel.{ Fiber, Outcome }
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LLLL_Result

@JCStressTest
@State
@Description("async register/complete/cancel race")
@Outcomes(Array(
  new JOutcome(id = Array("foo, Succeeded(IO(foo)), (), null"), expect = ACCEPTABLE_INTERESTING, desc = "succeeded"),
  new JOutcome(id = Array("null, Canceled(), (), null"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled early"),
  new JOutcome(id = Array("foo, Canceled(), (), null"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled late"), // FORBIDDEN?
))
class CatsAsyncTest {

  private[this] val runtime: IORuntime =
    CatsAsyncTest.runtime

  private[this] val cb: AtomicReference[Either[Throwable, String] => Unit] =
    new AtomicReference

  private[this] val dummyCb: (Either[Throwable, String] => Unit) =
    CatsAsyncTest.dummyCb

  @volatile
  private[this] var fib: Fiber[IO, Throwable, String] =
    null

  private[this] val res: Either[Throwable, String] =
    Right("foo")

  @Actor
  def register(r: LLLL_Result): Unit = {
    val a: IO[String] = IO.uncancelable { poll =>
      poll(
        IO.async[String] { cb =>
          IO { this.cb.set(cb) }.as(Some(IO.unit))
        }
      ).flatTap { s =>
        IO { r.r1 = s }
      }
    }

    val t: IO[Outcome[IO, Throwable, String]] = IO.uncancelable { poll =>
      // timeout, because JCStress first runs the actors
      // sequentially, and we would deadlock otherwise:
      IO.both(a.start, IO.sleep(1.second).start).flatMap {
        case (fib, sleepFib) =>
          IO { this.fib = fib } *> poll(IO.race(fib.join, sleepFib.join)).flatMap {
            case Left(oc) =>
              // `a` finished (or was canceled by `cancel`)
              sleepFib.cancel.as(oc)
            case Right(_) =>
              // timeout, so we just cancel `a`
              IO { r.r4 = "timeout" } *> fib.cancel *> fib.join
          }
      }
    }

    r.r2 = t.unsafeRunSync()(this.runtime).toString()

    // make sure `complete` will not spin forever:
    if (this.cb.compareAndSet(null, this.dummyCb)) {
      // it was cancelled very early(?)
      val msg = "cb eq null"
      r.r4 match {
        case null =>
          r.r4 = msg
        case s: String =>
          r.r4 = s + "," + msg
        case x =>
          r.r4 = x.toString() + "," + msg
      }
    }

    // make sure `cancel` will not spin forever:
    if (this.fib eq null) {
      this.fib = CatsAsyncTest.dummyFib
      // this should never happen:
      throw new Exception("fib eq null")
    }
  }

  @Actor
  def complete(): Unit = {
    var cb: (Either[Throwable, String] => Unit) = null
    while (cb eq null) {
      cb = this.cb.get()
    }
    cb(this.res)
  }

  @Actor
  def cancel(r: LLLL_Result): Unit = {
    var fib: Fiber[IO, Throwable, String] = null
    while (fib eq null) {
      fib = this.fib
    }
    r.r3 = fib.cancel.unsafeRunSync()(this.runtime)
  }
}

final object CatsAsyncTest {

  val runtime: IORuntime = {
    val (wstp, fin) = IORuntime.createWorkStealingComputeThreadPool(threads = 3)
    IORuntime.builder().setCompute(wstp, fin).build()
  }

  val dummyCb: (Either[Throwable, String] => Unit) = {
    { _ => () }
  }

  val dummyFib: Fiber[IO, Throwable, String] =
    IO.never[String].start.unsafeRunSync()(this.runtime)

  // def main(args: Array[String]): Unit = {
  //   val obj = new CatsAsyncTest
  //   val r = new LLLL_Result
  //   obj.register(r)
  //   obj.complete()
  //   obj.cancel(r)
  //   println(r)
  // }
}
