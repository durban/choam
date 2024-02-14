/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicInteger

import org.jetbrains.kotlinx.lincheck.{ LinChecker, LincheckAssertionError }
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

import ManagedTestExample._

final class ManagedTestExample extends FunSuite with BaseLinchkSpec {

  test("Dummy counter test".tag(SLOW)) {
    val opts = defaultModelCheckingOptions()
    try {
      LinChecker.check(classOf[BadCounterState], opts)
      fail("expected a lincheck failure")
    } catch {
      case _: LincheckAssertionError =>
        () // ok, expected failure
    }
  }

  test("Counter test which fails".tag(SLOW).fail) {
    val opts = defaultModelCheckingOptions()
    LinChecker.check(classOf[BadCounterState], opts)
  }

  test("Counter test which passes".tag(SLOW)) {
    val opts = defaultModelCheckingOptions()
    LinChecker.check(classOf[GoodCounterState], opts)
  }
}

object ManagedTestExample {

  @Param(name = "v", gen = classOf[IntGen], conf = "0:127")
  sealed abstract class AbstractState {

    protected def incrementBy(i: Int): Int

    protected def decrementBy(i: Int): Int

    @Operation
    def incr(v: Int): Int =
      incrementBy(v)

    @Operation
    def decr(v: Int): Int =
      decrementBy(v)
  }

  class BadCounterState extends AbstractState {

    @volatile
    private[this] var count: Int =
      0

    protected override def incrementBy(v: Int): Int = {
      val curr = this.count
      this.count = curr + v
      curr
    }

    protected override def decrementBy(v: Int): Int = {
      val curr = this.count
      this.count = curr - v
      curr
    }
  }

  class GoodCounterState extends AbstractState {

    private[this] val count =
      new AtomicInteger

    protected override def incrementBy(i: Int): Int =
      count.getAndAdd(i)

    protected override def decrementBy(i: Int): Int =
      count.getAndAdd(-i)
  }
}
