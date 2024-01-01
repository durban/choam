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

import refs.Ref1

final class Ref1SpecP1 extends Ref1Spec {

  override def mkRef1[A](a: A): Ref1[A] =
    Ref1.unsafe(a)
}

final class Ref1SpecU1 extends Ref1Spec {

  override def mkRef1[A](a: A): Ref1[A] =
    Ref1.unsafeUnpadded(a)
}

abstract class Ref1Spec extends BaseSpec {

  def mkRef1[A](a: A): Ref1[A]

  test("toString format") {
    val pat = "Ref\\@[\\da-f]{16}".r
    assert(pat.matches(clue(mkRef1("a").toString)))
  }

  test("equals/toString") {
    val r1 = mkRef1[String]("a")
    val r2 = mkRef1[String]("a")
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1.toString != r2.toString)
  }

  test("read/write/cas") {
    val r = mkRef1[String]("a").loc
    assert(r.unsafeGetVolatile() eq "a")
    r.unsafeSetVolatile("b")
    assert(r.unsafeGetVolatile() eq "b")
    assert(r.unsafeCasVolatile("b", "c"))
    assert(r.unsafeGetVolatile() eq "c")
  }
}
