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

package foo

import java.lang.Runtime

import scala.scalajs.js.isUndefined
import scala.scalajs.LinkingInfo.esVersion
import scala.scalajs.js

import munit.FunSuite

final class FooSpecJs extends FunSuite {

  test("Foo (JS)") {
    assertEquals(Foo.foo, 42)
    assert(isUndefined(()))
    assert(!isUndefined(42))
    println(s"ES version: ${esVersion}")
    println("NUM_CPU: " + Runtime.getRuntime().availableProcessors().toString())
    println(s"Process: ${js.Dynamic.global.process.title} ${js.Dynamic.global.process.version}")
  }
}
