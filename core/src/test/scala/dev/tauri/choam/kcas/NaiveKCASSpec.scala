/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

class NaiveKCASSpec extends BaseSpecA {

  test("NaiveKCAS should not create EMCASWeakData objects") {
    val r1 = Ref.mk[String]("x")
    val r2 = Ref.mk[String]("y")
    val ctx = NaiveKCAS.currentContext()
    val desc = NaiveKCAS.addCas(NaiveKCAS.addCas(NaiveKCAS.start(ctx), r1, "x", "y"), r2, "y", "x")
    desc.words.iterator().forEachRemaining { wd => assert(wd.holder eq null) }
    val snap = NaiveKCAS.snapshot(desc)
    assert(NaiveKCAS.tryPerform(desc, ctx))
    snap.words.iterator().forEachRemaining { wd => assert(wd.holder eq null) }
    assertEquals(r1.unsafeTryRead(), "y")
    assertEquals(r2.unsafeTryRead(), "x")
    assert(!NaiveKCAS.tryPerform(snap, ctx))
    desc.words.iterator().forEachRemaining { wd => assert(wd.holder eq null) }
    snap.words.iterator().forEachRemaining { wd => assert(wd.holder eq null) }
    assertEquals(r1.unsafeTryRead(), "y")
    assertEquals(r2.unsafeTryRead(), "x")
  }
}
