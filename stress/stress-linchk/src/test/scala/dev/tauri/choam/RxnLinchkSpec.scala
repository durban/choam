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

import munit.FunSuite

import internal.mcas.{ Mcas, OsRng }

trait RxnLinchkSpec extends BaseLinchkSpec { this: FunSuite =>

  override def beforeAll(): Unit = {
    super.beforeAll()
    RxnLinchkSpec.gcThreadContexts()
  }

  override def afterAll(): Unit = {
    RxnLinchkSpec.gcThreadContexts()
    super.afterAll()
  }
}

/**
 * We use a single global Emcas instance for
 * running model tests, which is useful, because
 * this way, e.g., version numbers aren't starting
 * from the minimum for every test. However, as
 * newer threads are created by lincheck, the
 * `_threadContexts` skiplist in `GlobalContext`
 * can grow quite large. And this can cause lincheck
 * to detect a spinlock while we're just traversing
 * the skiplist. (Probably.) So we're trying to
 * remove old unused nodes before and after each
 * test suite.
 */
object RxnLinchkSpec {

  private type GlobalContextLike = {
    def gcThreadContextsForTesting(): Unit
  }

  val defaultMcasForTesting: Mcas =
    Mcas.newDefaultMcas(OsRng.mkNew())

  private final def gcThreadContexts(): Unit = {
    println("Collecting garbage...")
    import scala.language.reflectiveCalls
    this.defaultMcasForTesting.asInstanceOf[GlobalContextLike].gcThreadContextsForTesting()
  }
}
