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

package dev.tauri.choam
package bench
package util

final class ChoiceCombinatorBenchSpec extends BaseSpecA {

  test("sanity check") {
    val b = new ChoiceCombinatorBench
    val s = new ChoiceCombinatorBench.CASChoice
    s.size = 8
    s.setup()
    val k = new KCASImplState
    k.kcasName = kcas.KCAS.fqns.EMCAS
    k.setupKCASImpl()
    b.doChoiceCAS(s, k)
    // check that the reaction happened:
    val ctx = kcas.EMCAS.currentContext()
    assertEquals(kcas.EMCAS.read(s.ref, ctx), "bar")
    for (r <- s.refs) {
      val v = kcas.EMCAS.read(r, ctx)
      assertEquals(v, "foo")
    }
    // re-run the reaction:
    s.reset.reset()
    assertEquals(kcas.EMCAS.read(s.ref, ctx), "foo")
    b.doChoiceCAS(s, k)
    assertEquals(kcas.EMCAS.read(s.ref, ctx), "bar")
  }
}
