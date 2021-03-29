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

import kcas.Ref
import ref.Ref2

class Ref2Spec extends BaseSpecA {

  test("Ref2 equality/toString") {
    val rr = Ref.ref2[String, Int]("a", 42)
    val Ref2(r1, r2) = rr
    assert((rr : AnyRef) ne r1)
    assert((rr : Any) != r1)
    assert((rr : AnyRef) ne r2)
    assert((rr : Any) != r2)
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1 eq rr._1)
    assert(r1 == rr._1)
    assert(r2 eq rr._2)
    assert(r2 == rr._2)
    assert(r1.toString != r2.toString)
  }

  test("Ref2 consistentRead") {
    val rr = Ref.ref2[String, Int]("a", 42)
    val (s, i) = rr.consistentRead.unsafePerform((), kcas.KCAS.EMCAS)
    assert(s eq "a")
    assert(i == 42)
  }
}
