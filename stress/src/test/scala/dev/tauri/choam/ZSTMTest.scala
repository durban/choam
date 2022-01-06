/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.LongAdder

import zio.IO
import zio.stm.{ STM, ZSTM, TRef }

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

// @JCStressTest
@State
@Description("ZSTM")
@Outcomes(Array(
  new Outcome(id = Array("Success(()), Success(()), 0"), expect = ACCEPTABLE, desc = "OK")
))
class ZSTMTest {

  import ZSTMTest._

  private[this] val rt =
    zio.Runtime.default

  private[this] val q: Something =
    rt.unsafeRunTask(Something.apply)

  private[this] val ctr: LongAdder =
    new LongAdder

  private[this] def task(ctr: LongAdder): IO[Throwable, Unit] = {
    val txns = (0 until ZSTMTest.N).map(_ => q.write(ctr))
    IO.foreachParDiscard(txns)(ZSTM.atomically)
  }

  @Actor
  def write1(r: LLL_Result): Unit = {
    r.r1 = rt.unsafeRunSync(task(ctr))
  }

  @Actor
  def write2(r: LLL_Result): Unit = {
    r.r2 = rt.unsafeRunSync(task(ctr))
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = ctr.sum()
  }
}

object ZSTMTest {

  final object Something {
    def apply: IO[Nothing, Something] = for {
      // initial value is "A":
      inner <- TRef.makeCommit("A")
      outer <- TRef.makeCommit(inner)
    } yield new Something(outer)
  }

  final val N = 10

  final class Something(outer: TRef[TRef[String]]) {

    def write(ctr: LongAdder): STM[Throwable, String] = {
      // new ref also contains "A":
      TRef.make("A").flatMap { fresh =>
        outer.get.flatMap { inner =>
          inner.get.flatMap { s =>
            if (s != "A") {
              // we should never read anything other than "A":
              ctr.increment()
              ZSTM.retry
            } else {
              // we write "X", but we never commit it
              // (because we replace the inner ref):
              inner.set("X") *> outer.set(fresh).as(s)
            }
          }
        }
      }
    }
  }
}
