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

import java.util.concurrent.atomic.AtomicReference

import munit.FunSuite

import internal.mcas.Mcas

trait RxnLinchkSpec extends BaseLinchkSpec { this: FunSuite =>

  override def beforeEach(ctx: BeforeEach): Unit = {
    super.beforeEach(ctx)
    RxnLinchkSpec.init()
  }

  override def afterEach(ctx: AfterEach): Unit = {
    RxnLinchkSpec.cleanup()
    super.afterEach(ctx)
  }
}

/**
 * We use a single global Emcas instance for
 * running model tests, which is useful, because
 * this way, e.g., version numbers aren't starting
 * from the minimum for every iteration. However, as
 * newer threads are created by lincheck, the
 * `_threadContexts` skiplist in `GlobalContext`
 * can grow quite large. And this can cause lincheck
 * to detect a spinlock while we're just traversing
 * the skiplist. (Probably.)
 *
 * So we're doing this ugly hack: replacing the
 * global instance with a fresh one (in `beforeEach`),
 * and closing the old one (in `afterEach`).
 */
object RxnLinchkSpec {

  private[this] val _rtHolder =
    new AtomicReference[ChoamRuntime](null)

  final def defaultMcasForTesting: Mcas =
    _rtHolder.getAcquire().mcasImpl

  private final def init(): Unit = {
    val newInst = ChoamRuntime.unsafeBlocking()
    val oldInst = _rtHolder.getAndSet(newInst)
    assert(oldInst eq null)
  }

  private final def cleanup(): Unit = {
    val oldInst = _rtHolder.getAndSet(null)
    assert(oldInst ne null)
    oldInst.unsafeCloseBlocking()
  }
}
