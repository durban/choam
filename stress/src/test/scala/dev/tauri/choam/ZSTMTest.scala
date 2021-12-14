/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import zio.IO
import zio.stm.{ STM, ZSTM, TRef }

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

// @JCStressTest
@State
@Description("ZSTM")
@Outcomes(Array(
  new Outcome(id = Array("Success(()), Success(())"), expect = ACCEPTABLE, desc = "OK")
))
class ZSTMTest {

  import ZSTMTest._

  private[this] val rt =
    zio.Runtime.default

  private[this] val q: Something =
    rt.unsafeRunTask(Something.apply)

  private[this] val task: IO[Throwable, Unit] = {
    val txns = (0 until ZSTMTest.N).map(_ => q.write)
    IO.foreachPar_(txns)(ZSTM.atomically)
  }

  @Actor
  def write1(r: LL_Result): Unit = {
    r.r1 = rt.unsafeRunSync(task)
  }

  @Actor
  def write2(r: LL_Result): Unit = {
    r.r2 = rt.unsafeRunSync(task)
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

    def write: STM[Throwable, String] = {
      // new ref also contains "A":
      TRef.make("A").flatMap { fresh =>
        outer.get.flatMap { inner =>
          inner.get.flatMap { s =>
            if (s != "A") {
              // we should never read anything other than "A":
              ZSTM.fail(new IllegalStateException("impossible: " + s))
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
