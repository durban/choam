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

import scala.util.Try

import munit.Location

final class RefArraySpec_Strict extends RefArraySpec {

  final override def mkRefArray[A](a: A, size: Int = N): Ref.Array[A] =
    Ref.unsafeStrictArray(size = size, initial = a)
}

final class RefArraySpec_Lazy extends RefArraySpec {

  final override def mkRefArray[A](a: A, size: Int = N): Ref.Array[A] =
    Ref.unsafeLazyArray(size = size, initial = a)
}

trait RefArraySpec extends BaseSpecA {

  final val N = 4

  def mkRefArray[A](a: A, size: Int = N): Ref.Array[A]

  test("empty array") {
    val arr = mkRefArray("foo", 0)
    val pat = "RefArray\\[0\\]\\@[\\da-f]+".r
    assert(pat.matches(clue(arr.toString)))
    assert(Try { arr.unsafeGet(0) }.isFailure)
  }

  test("indexing error") {
    def checkError(op: => Ref[String])(implicit loc: Location): Unit = {
      val ok = Try(op).failed.get.isInstanceOf[IllegalArgumentException]
      assert(ok)(loc)
    }
    val arr1 = mkRefArray("foo", 4) // even
    checkError { arr1.unsafeGet(4) }
    checkError { arr1.unsafeGet(5) }
    checkError { arr1.unsafeGet(-1) }
    checkError { arr1.unsafeGet(Int.MinValue) }
    checkError { arr1.unsafeGet(Int.MaxValue) }
    val arr2 = mkRefArray("foo", 5) // odd
    checkError { arr2.unsafeGet(5) }
    checkError { arr2.unsafeGet(6) }
    checkError { arr2.unsafeGet(-1) }
    checkError { arr2.unsafeGet(Int.MinValue) }
    checkError { arr2.unsafeGet(Int.MaxValue) }
  }

  test("safe indexing") {
    val arr = mkRefArray("foo", 4)
    assert(arr.apply(Int.MinValue).isEmpty)
    assert(arr.apply(-1).isEmpty)
    assert(arr.apply(0).isDefined)
    assert(arr.apply(1).isDefined)
    assert(arr.apply(2).isDefined)
    assert(arr.apply(3).isDefined)
    assert(arr.apply(4).isEmpty)
    assert(arr.apply(5).isEmpty)
    assert(arr.apply(6).isEmpty)
    assert(arr.apply(1024).isEmpty)
    assert(arr.apply(Int.MaxValue).isEmpty)
  }

  test("toString format") {
    val arr = mkRefArray("a")
    val pat = "RefArray\\[\\d+\\]\\@[\\da-f]+".r
    assert(pat.matches(clue(arr.toString)))
    val subPat = "ARef\\@[\\da-f]{16}".r
    assert(subPat.matches(clue(arr.unsafeGet(0).toString)))
    assert(clue(arr.unsafeGet(0).toString).endsWith("0000"))
    assert(clue(arr.unsafeGet(1).toString).endsWith("0001"))
    assert(clue(arr.unsafeGet(2).toString).endsWith("0002"))
  }

  test("equals/toString") {
    val a: Ref.Array[String] = mkRefArray[String]("a")
    val r1: Ref[String] = a.unsafeGet(0)
    val r2: Ref[String] = a.unsafeGet(1)
    assert((a : AnyRef) ne r1)
    assert((a : Any) != r1)
    assert((a : AnyRef) ne r2)
    assert((a : Any) != r2)
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1 eq a.unsafeGet(0))
    assert(r1 == a.unsafeGet(0))
    assert(r2 eq a.unsafeGet(1))
    assert(r2 == a.unsafeGet(1))
    assert(r1.toString != r2.toString)
    assertEquals(r1.loc.id0, r2.loc.id0)
    assertEquals(r1.loc.id1, r2.loc.id1)
    assertEquals(r1.loc.id2, r2.loc.id2)
    assertEquals(r1.loc.id3 + 1L, r2.loc.id3)
  }

  test("consistentRead") {
    val a = mkRefArray[Int](42)
    a.unsafeGet(0).update(_ + 1).unsafeRun(mcas.Mcas.DefaultMcas)
    val (x, y) = Rxn.consistentRead(a.unsafeGet(0), a.unsafeGet(2)).unsafeRun(mcas.Mcas.DefaultMcas)
    assert(x == 43)
    assert(y == 42)
  }

  test("read/write/cas") {
    val a = mkRefArray[String]("a")
    val r1 = a.unsafeGet(1).loc
    val r2 = a.unsafeGet(2).loc
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
