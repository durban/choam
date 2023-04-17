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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.{ IntGen, StringGen }
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param, StateRepresentation }

import munit.FunSuite

final class SkipListModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking test of SkipListMap") {
    val opts = defaultModelCheckingOptions()
      .checkObstructionFreedom(true)
      .iterations(500) // 1000
      .invocationsPerIteration(200) // 1000
      .threads(2)
      .actorsBefore(5)
      .actorsPerThread(4)
      .actorsAfter(3)
      .sequentialSpecification(classOf[SkipListModelTest.TestStateSequential])
    printFatalErrors {
      LinChecker.check(classOf[SkipListModelTest.TestState], opts)
    }
  }
}

final object SkipListModelTest {

  @Param(name = "key", gen = classOf[IntGen])
  @Param(name = "value", gen = classOf[StringGen])
  class TestState {

    private[this] val m: SkipListMap[Int, String] =
      new SkipListMap

    @StateRepresentation
    def stateRepr(): String = {
      val lst = List.newBuilder[String]
      m.foreachNode { n =>
        lst += s"[${n.key}, ${n.getValue()}]"
      }
      lst.result().mkString("{", "; ", "}")
    }

    @Operation
    def insert(key: Int, value: String): Unit = {
      m.insertTlr(key, value)
      ()
    }

    // TODO:
    // @Operation
    // def lookup(key: Int): String = {
    //   // ...
    // }

    @Operation
    def remove(key: Int): Boolean = {
      m.remove(key)
    }
  }

  @Param(name = "key", gen = classOf[IntGen])
  @Param(name = "value", gen = classOf[StringGen])
  class TestStateSequential extends VerifierState {

    private[this] val m =
      scala.collection.mutable.TreeMap.empty[Int, String]

    override def extractState(): Map[Int, String] = {
      m.toMap
    }

    @StateRepresentation
    def stateRepr(): String = {
      m.toList.map {
        case (k, cb) => s"[${k}, ${cb}]"
      }.mkString("{", "; ", "}")
    }

    @Operation
    def insert(key: Int, value: String): Unit = {
      m.put(key, value)
      ()
    }

    // TODO:
    // @Operation
    // def lookup(key: Int): String = {
    //   // ...
    // }

    @Operation
    def remove(key: Int): Boolean = {
      m.remove(key).isDefined
    }
  }
}
