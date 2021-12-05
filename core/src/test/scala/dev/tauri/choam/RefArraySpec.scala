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

final class RefArraySpec extends BaseSpecA {

  final val N = 4

  def mkRefArray[A](a: A): Ref.Array[A] =
    Ref.unsafeArray(size = N, initial = a)

  test("toString format") {
    val arr = mkRefArray("a")
    val pat = "RefArray\\[\\d+\\]\\@[\\da-f]+".r
    assert(pat.matches(clue(arr.toString)))
    val subPat = "ARef\\@[\\da-f]+".r
    assert(subPat.matches(clue(arr(0).toString)))
    assert(arr(0).toString.endsWith("0000"))
    assert(arr(1).toString.endsWith("0001"))
    assert(arr(2).toString.endsWith("0002"))
  }

  test("equals/toString") {
    val a: Ref.Array[String] = mkRefArray[String]("a")
    val r1: Ref[String] = a(0)
    val r2: Ref[String] = a(1)
    assert((a : AnyRef) ne r1)
    assert((a : Any) != r1)
    assert((a : AnyRef) ne r2)
    assert((a : Any) != r2)
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1 eq a(0))
    assert(r1 == a(0))
    assert(r2 eq a(1))
    assert(r2 == a(1))
    assert(r1.toString != r2.toString)
    assertEquals(r1.loc.id0, r2.loc.id0)
    assertEquals(r1.loc.id1, r2.loc.id1)
    assertEquals(r1.loc.id2, r2.loc.id2)
    assertEquals(r1.loc.id3 + 1L, r2.loc.id3)
  }

  test("consistentRead") {
    val a = mkRefArray[Int](42)
    a(0).update(_ + 1).unsafeRun(kcas.KCAS.EMCAS)
    val (x, y) = Rxn.consistentRead(a(0), a(2)).unsafeRun(kcas.KCAS.EMCAS)
    assert(x == 43)
    assert(y == 42)
  }

  test("read/write/cas") {
    val a = mkRefArray[String]("a")
    val r1 = a(1).loc
    val r2 = a(2).loc
    assert(r1.unsafeGetVolatile() eq "a")
    assert(r2.unsafeGetVolatile() eq "a")
    r1.unsafeSetVolatile("b")
    assert(r1.unsafeGetVolatile() eq "b")
    assert(r2.unsafeGetVolatile() eq "a")
    r2.unsafeSetVolatile("x")
    assert(r1.unsafeGetVolatile() eq "b")
    assert(r2.unsafeGetVolatile() eq "x")
    assert(r1.unsafeCasVolatile("b", "c"))
    assert(r1.unsafeGetVolatile() eq "c")
    assert(r2.unsafeGetVolatile() eq "x")
    assert(!r2.unsafeCasVolatile("-", "+"))
    assert(r1.unsafeGetVolatile() eq "c")
    assert(r2.unsafeGetVolatile() eq "x")
  }
}