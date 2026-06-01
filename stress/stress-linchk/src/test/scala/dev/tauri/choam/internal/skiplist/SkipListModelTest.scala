/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package skiplist

import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.lincheck.datastructures.{ Operation, Param }

import munit.FunSuite

final class SkipListModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking test of SkipListMap".tag(SLOW)) {
    val opts = mediumModelCheckingOptions()
      .sequentialSpecification(classOf[SkipListModelTest.TestStateSequential])
    printFatalErrors {
      opts.check(classOf[SkipListModelTest.TestState])
    }
  }
}

object SkipListModelTest {

  @Param(name = "key", gen = classOf[IntGen])
  @Param(name = "value", gen = classOf[IntGen], conf = "1:5")
  class TestState {

    private[this] val m: SkipListMap[Int, Int] =
      new SkipListMap

    @Operation
    def put(key: Int, value: Int): Option[Int] = {
      m.put(key, value)
    }

    @Operation
    def putIfAbsent(key: Int, value: Int): Option[Int] = {
      m.putIfAbsent(key, value)
    }

    @Operation
    def lookup(key: Int): Option[Int] = {
      m.get(key)
    }

    @Operation
    def del(key: Int): Boolean = {
      m.del(key)
    }

    @Operation
    def remove(key: Int, value: Int): Boolean = {
      // Note: `SkipListMap#remove` uses reference
      // equality, so here we depend on small `Int`s
      // having a stable identity.
      m.remove(key, value)
    }
  }

  @Param(name = "key", gen = classOf[IntGen])
  @Param(name = "value", gen = classOf[IntGen], conf = "1:5")
  class TestStateSequential {

    private[this] val m =
      scala.collection.mutable.TreeMap.empty[Int, Int]

    @Operation
    def put(key: Int, value: Int): Option[Int] = {
      m.put(key, value)
    }

    @Operation
    def putIfAbsent(key: Int, value: Int): Option[Int] = {
      m.get(key) match {
        case s @ Some(_) =>
          s
        case None =>
          m.put(key, value)
          None
      }
    }

    @Operation
    def lookup(key: Int): Option[Int] = {
      m.get(key)
    }

    @Operation
    def del(key: Int): Boolean = {
      m.remove(key).isDefined
    }

    @Operation
    def remove(key: Int, value: Int): Boolean = {
      m.get(key) match {
        case Some(other) if (value == other) =>
          m.remove(key)
          true
        case Some(_) =>
          false
        case None =>
          false
      }
    }
  }
}
