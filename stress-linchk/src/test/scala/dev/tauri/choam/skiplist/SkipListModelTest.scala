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
package skiplist

import java.lang.Long.{ MIN_VALUE, MAX_VALUE }

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.{ ParameterGenerator, IntGen }
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

final class SkipListModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking test of TimerSkipList") {
    val opts = defaultModelCheckingOptions()
      .checkObstructionFreedom(true)
      .iterations(500)
      .invocationsPerIteration(200)
      .threads(2)
      .actorsBefore(30)
      .actorsPerThread(5)
      .actorsAfter(0)
    printFatalErrors {
      LinChecker.check(classOf[SkipListModelTest.TestState], opts)
    }
  }
}

final object SkipListModelTest {

  type Callback = Function1[Right[Nothing, Unit], Unit]

  final class CbGen(configuration: String) extends ParameterGenerator[Callback] {

    private[this] val intGen = new IntGen(configuration)

    final override def generate(): Callback = {
      new Function1[Right[Nothing, Unit], Unit] with Serializable {
        @unused val id: Int = intGen.generate().intValue()
        final override def apply(r: Right[Nothing, Unit]): Unit = ()
        final override def toString: String = s"Callback(#${id.toHexString})"
      }
    }
  }

  final class NonNegativeLongGen(configuration: String) extends ParameterGenerator[Long] {

    private[this] val intGen = new IntGen(configuration)

    final override def generate(): Long = {
      val n = intGen.generate().toLong
      if (n < 0) -n else n
    }
  }

  @Param(name = "delay", gen = classOf[NonNegativeLongGen])
  @Param(name = "callback", gen = classOf[CbGen])
  class TestState extends VerifierState {

    private[this] val m: TimerSkipList =
      new TimerSkipList

    override def extractState(): List[Callback] = {
      val lb = List.newBuilder[Callback]
      while (true) {
        val cb = m.pollFirstIfTriggered(now = MAX_VALUE)
        if (cb ne null) {
          lb += cb
        } else {
          return lb.result() // scalafix:ok
        }
      }

      null // unreachable
    }

    @Operation
    def insert(delay: Long, callback: Callback): Long = {
      val c = m.insertTlr(now = MIN_VALUE, delay = delay, callback = callback).asInstanceOf[m.Canceller]
      c.triggerTime
    }

    @Operation
    def pollFirstIfTriggered(): Any = {
      m.pollFirstIfTriggered(now = MAX_VALUE)
    }
  }
}
