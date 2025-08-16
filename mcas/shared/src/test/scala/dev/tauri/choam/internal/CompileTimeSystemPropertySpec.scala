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
package internal

final class CompileTimeSystemPropertySpec extends BaseSpec {

  test("getBoolean") {
    assert(CompileTimeSystemPropertySpec.shouldBeTrue)
    assert(!CompileTimeSystemPropertySpec.shouldBeFalse)
    assert(!CompileTimeSystemPropertySpec.shouldBeFalseButTrueRuntime)
    if (isJvm()) {
      assert(getBoolean(CompileTimeSystemPropertySpec.testPropertyName))
    }
  }

  private[this] final def getBoolean(name: String): Boolean = {
    java.lang.Boolean.parseBoolean(System.getProperty(name))
  }
}

object CompileTimeSystemPropertySpec {

  final val testPropertyName =
    "dev.tauri.choam.testProperty"

  final val shouldBeTrue =
    CompileTimeSystemProperty.getBoolean("dev.tauri.choam.stats")

  final val shouldBeFalse =
    CompileTimeSystemProperty.getBoolean("dev.tauri.choam.noSuchProperty")

  final val shouldBeFalseButTrueRuntime =
    CompileTimeSystemProperty.getBoolean(testPropertyName)
}
