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
package data

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

import internal.mcas.Mcas

import QueueModelTest._

final class QueueModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking MsQueue".tag(SLOW)) {
    queueModelCheck(classOf[MsQueueTestState])
  }

  private def queueModelCheck(cls: Class[? <: AbstractTestState]): Unit = {
    printFatalErrors {
      LinChecker.check(cls, defaultModelCheckingOptions())
    }
  }
}

private[data] object QueueModelTest {

  @Param(name = "s", gen = classOf[StringGen])
  sealed abstract class AbstractTestState {

    protected[this] val emcas: Mcas =
      BaseLinchkSpec.defaultMcasForTesting

    protected[this] val q: Queue[String]

    @Operation
    def enq(s: String): Unit = {
      q.enqueue(s).unsafePerform(emcas)
    }

    @Operation
    def tryDeq(): Option[String] = {
      q.tryDeque.unsafePerform(emcas)
    }
  }

  class MsQueueTestState extends AbstractTestState {
    protected[this] override val q: Queue[String] =
      MsQueue[String].unsafePerform(emcas)
  }
}
