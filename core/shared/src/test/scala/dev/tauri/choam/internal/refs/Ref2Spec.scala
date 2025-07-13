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
package internal
package refs

import core.Ref2

final class Ref2SpecP1P1 extends Ref2Spec {

  override def mkRef2[A, B](a: A, b: B): Ref2[A, B] =
    Ref2.p1p1(a, b).unsafePerform(this.mcasImpl)
}

final class Ref2SpecP2 extends Ref2Spec {

  override def mkRef2[A, B](a: A, b: B): Ref2[A, B] =
    Ref2.p2(a, b).unsafePerform(this.mcasImpl)
}

abstract class Ref2Spec extends BaseSpec with SpecDefaultMcas {

  def mkRef2[A, B](a: A, b: B): Ref2[A, B]

  test("toString format") {
    val r2 = mkRef2("a", "b")
    val pat2 = "Ref2\\@[\\da-f]{16}".r
    assert(pat2.matches(clue(r2.toString)))
    val pat = "Ref@[\\da-f]{16}".r
    assert(pat.matches(clue(r2._1.toString)))
    assert(pat.matches(clue(r2._2.toString)))
  }

  test("equals/toString") {
    val rr = mkRef2[String, Int]("a", 42)
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

  test("consistentRead") {
    val rr = mkRef2[String, Int]("a", 42)
    val (s, i) = rr.consistentRead.unsafePerform(this.defaultMcasInstance)
    assert(s eq "a")
    assert(i == 42)
  }

  test("read/write/cas") {
    final class Foo
    val foo = new Foo
    val rr = mkRef2[String, Foo]("a", foo)
    val r1 = rr._1.loc
    val r2 = rr._2.loc
    assert(r1.unsafeGetV() eq "a")
    assert(r2.unsafeGetV() eq foo)
    r1.unsafeSetV("b")
    assert(r1.unsafeGetV() eq "b")
    assert(r2.unsafeGetV() eq foo)
    r2.unsafeSetV(new Foo)
    assert(r1.unsafeGetV() eq "b")
    assert(r2.unsafeGetV() ne foo)
    assert(r1.unsafeCasV("b", "c"))
    assert(r1.unsafeGetV() eq "c")
    assert(r2.unsafeGetV() ne foo)
    assert(!r2.unsafeCasV(foo, new Foo))
    assert(r1.unsafeGetV() eq "c")
    assert(r2.unsafeGetV() ne foo)
  }
}
