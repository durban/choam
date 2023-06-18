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

import random.OsRng

final class EnvironmentSpec extends EnvironmentSpecPlatform {

  test("Check environment") {
    for (prop <- sysProps) {
      printSystemProperty(prop)
    }
    println(s"Runtime.getRuntime().availableProcessors() == ${Runtime.getRuntime().availableProcessors()}")
  }

  test("Check osRandom") {
    val or = OsRng.mkNew()
    println(s"OsRandom class: ${or.getClass().getName()}")
    or.nextBytes(256)
  }

  test("Check autodetection") {
    println(s"isOpenJdk() == ${isOpenJdk()}")
    println(s"isOpenJ9() == ${isOpenJ9()}")
    println(s"isWindows() == ${isWindows()}")
    println(s"isJvm() == ${isJvm()}")
    println(s"isJs() == ${isJs()}")
    println(s"isVmSupportsLongCas() == ${isVmSupportsLongCas()}")
    println(s"isIntCached(42) == ${isIntCached(42)}")
    println(s"isIntCached(99999999) == ${isIntCached(99999999)}")
  }

  private val sysProps = List(
    "java.vendor",
    "java.vendor.url",
    "java.version",
    "java.vm.name",
    "os.arch",
    "os.name",
    "os.version",
  )

  private def printSystemProperty(name: String): Unit = {
    val value = System.getProperty(name)
    val msg = s"${name} property == \"${value}\""
    println(msg)
  }
}
