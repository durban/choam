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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

import internal.mcas.Mcas

import TtrieModelTest._

final class TtrieModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking Ttrie.apply".tag(SLOW)) {
    ttrieModelCheck(classOf[TrieMapTestState])
  }

  test("Model checking Ttrie.skipListBased".tag(SLOW).ignore) { // TODO: this fails, probably linchk bug
    ttrieModelCheck(classOf[SkipListTestState])
  }

  private def ttrieModelCheck(cls: Class[_ <: AbstractTestState]): Unit = {
    printFatalErrors {
      LinChecker.check(cls, defaultModelCheckingOptions())
    }
  }
}

private[data] object TtrieModelTest {

  @Param(name = "k", gen = classOf[StringGen])
  @Param(name = "v", gen = classOf[StringGen])
  sealed abstract class AbstractTestState {

    protected[this] val emcas: Mcas =
      Mcas.Emcas

    protected[this] val m: Ttrie[String, String]

    @Operation
    def insert(k: String, v: String): Option[String] = {
      m.put.unsafePerform(k -> v, emcas)
    }

    @Operation
    def insertIfAbsent(k: String, v: String): Option[String] = {
      m.putIfAbsent.unsafePerform(k -> v, emcas)
    }

    @Operation
    def lookup(k: String): Option[String] = {
      m.get.unsafePerform(k, emcas)
    }

    @Operation
    def removeKey(k: String): Boolean = {
      m.del.unsafePerform(k, emcas)
    }
  }

  class TrieMapTestState extends AbstractTestState {
    protected[this] override val m: Ttrie[String, String] =
      Ttrie[String, String].unsafeRun(emcas)
  }

  class SkipListTestState extends AbstractTestState {
    protected[this] override val m: Ttrie[String, String] =
      Ttrie.skipListBased[String, String].flatMapF { (m: Ttrie[String, String]) =>
        m.get.provide("dummy").as(m) // FIXME?
      }.unsafeRun(emcas)
  }
}
