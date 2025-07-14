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

import scala.concurrent.duration._

import munit.{ FunSuite, BaseFunSuite, Location, TestOptions }

import internal.mcas.{ Mcas, OsRng }

trait BaseLinchkSpec extends BaseFunSuite with LinchkUtils with MUnitUtils { this: FunSuite =>

  override def munitTimeout: Duration =
    120.minutes

  final override def test(name: String)(body: => Any)(implicit loc: Location): Unit = {
    super[BaseFunSuite].test(name) {
      checkAssumptions()
      body
    }
  }

  final override def test(options: TestOptions)(body: => Any)(implicit loc: Location): Unit = {
    super[BaseFunSuite].test(options) {
      checkAssumptions()
      body
    }
  }

  private[this] def checkAssumptions(): Unit = {
    // lincheck tests seem unstable in CI windows:
    assumeNotWin()
    // sometimes lincheck tests just hang on certain
    // ARM JVMs, possibly due to this (UNSAFE.putInt):
    // https://github.com/JetBrains/lincheck/blob/lincheck-2.38/src/jvm/main/org/jetbrains/kotlinx/lincheck/strategy/managed/ObjectIdentityHashCodeTracker.kt#L72
    val armAndJvm11 = this.isArm() && (this.getJvmVersion() == 11)
    assume(!armAndJvm11, "this test doesn't run on ARM + JVM 11")
    // lincheck bytecode transformer doesn't work on newer
    // JVMs (note: for some reason these tests can be green,
    // but don't actually seem to run; so we just skip them):
    assume(this.getJvmVersion() < 24)
  }
}

object BaseLinchkSpec {
  val defaultMcasForTesting: Mcas =
    Mcas.newDefaultMcas(OsRng.mkNew())
}
