/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package core

import scala.util.Try

final class InternalStackSpec extends BaseSpecA {

  test("ObjStack") {
    val s = new ObjStack[String]
    assert(s.isEmpty)
    assert(!s.nonEmpty)
    assertEquals(s.toString, "ObjStack()")
    val s0 = s.takeSnapshot()
    s.push("a")
    assertEquals(s.toString, "ObjStack(a)")
    assert(!s.isEmpty)
    assert(s.nonEmpty)
    val s1 = s.takeSnapshot()
    s.push("b")
    assertEquals(s.toString, "ObjStack(b, a)")
    s.pushAll("c" :: "d" :: Nil)
    assertEquals(s.toString, "ObjStack(d, c, b, a)")
    val s2 = s.takeSnapshot()
    s.loadSnapshot(s1)
    assertSameInstance(s.pop(), "a")
    assert(s.isEmpty)
    s.loadSnapshot(s2)
    assertSameInstance(s.pop(), "d")
    assertSameInstance(s.pop(), "c")
    s.clear()
    assert(s.isEmpty)
    s.loadSnapshot(s0)
    assert(s.isEmpty)
    assert(Try { s.pop() }.isFailure)
  }

  test("ByteStack") {
    val s = new ByteStack(initSize = 2)
    assert(s.isEmpty)
    assert(!s.nonEmpty)
    val s0 = s.takeSnapshot()
    s.push(1.toByte)
    assert(!s.isEmpty)
    assert(s.nonEmpty)
    val s1 = s.takeSnapshot()
    s.push(2.toByte)
    s.push(3.toByte)
    s.push(4.toByte)
    val s2 = s.takeSnapshot()
    s.loadSnapshot(s1)
    assertEquals(s.pop(), 1.toByte)
    assert(s.isEmpty)
    s.loadSnapshot(s2)
    assertEquals(s.pop(), 4.toByte)
    assertEquals(s.pop(), 3.toByte)
    s.clear()
    assert(s.isEmpty)
    s.loadSnapshot(s0)
    assert(s.isEmpty)
    assert(Try { s.pop() }.isFailure)
  }

  private def lstFromAs[A](as: A*): ObjStack.Lst[A] = {
    val s = new ObjStack[A]
    as.toList.reverse.foreach(s.push)
    val res = s.takeSnapshot()
    assertEquals(ObjStack.Lst.mkString(res), as.mkString(", "))
    res
  }

  test("ObjStack.Lst.length") {
    import ObjStack.Lst
    assertEquals(Lst.length(null), 0)
    assertEquals(Lst.length(lstFromAs(1)), 1)
    assertEquals(Lst.length(lstFromAs(1, 2)), 2)
    assertEquals(Lst.length(lstFromAs(1, 2, 3)), 3)
    assertEquals(Lst.length(lstFromAs(1, 2, 3, 4)), 4)
  }

  test("ObjStack.Lst.concat") {
    import ObjStack.Lst
    assertEquals(Lst.concat(null, null), null)
    assertEquals(Lst.mkString(Lst.concat(lstFromAs(1), null)), "1")
    assertEquals(Lst.mkString(Lst.concat(null, lstFromAs(1))), "1")
    assertEquals(Lst.mkString(Lst.concat(lstFromAs(1), lstFromAs(2))), "1, 2")
    assertEquals(Lst.mkString(Lst.concat(lstFromAs(1), lstFromAs(2, 3))), "1, 2, 3")
    assertEquals(Lst.mkString(Lst.concat(lstFromAs(1, 2), lstFromAs(3))), "1, 2, 3")
    assertEquals(Lst.mkString(Lst.concat(lstFromAs(1, 2), lstFromAs(3, 4))), "1, 2, 3, 4")
    assertEquals(Lst.mkString(Lst.concat(lstFromAs(1, 2), null)), "1, 2")
    assertEquals(Lst.mkString(Lst.concat(null, lstFromAs(3, 4))), "3, 4")
    assertEquals(
      Lst.mkString(Lst.concat[Int](lstFromAs(1 to 100: _*), lstFromAs(200 to 300: _*))),
      ((1 to 100).toList ++ (200 to 300).toList).mkString(", ")
    )
  }

  test("ObjStack.Lst.splitBefore") {
    import ObjStack.Lst
    assertEquals(Lst.splitBefore[String](null, "a"), null)
    assertEquals(Lst.splitBefore[String](lstFromAs("x"), "a"), null)
    assertEquals(Lst.splitBefore[String](lstFromAs("x", "y"), "a"), null)
    val (a0, b0) = Lst.splitBefore[String](lstFromAs("a"), "a")
    assertEquals(a0, null)
    assertEquals(Lst.mkString(b0), "a")
    val (a1, b1) = Lst.splitBefore[String](lstFromAs("a", "b"), "a")
    assertEquals(a1, null)
    assertEquals(Lst.mkString(b1), "a, b")
    val (a2, b2) = Lst.splitBefore[String](lstFromAs("a", "b"), "b")
    assertEquals(Lst.mkString(a2), "a")
    assertEquals(Lst.mkString(b2), "b")
    val (a3, b3) = Lst.splitBefore[String](lstFromAs("a", "b", "c"), "b")
    assertEquals(Lst.mkString(a3), "a")
    assertEquals(Lst.mkString(b3), "b, c")
    val (a4, b4) = Lst.splitBefore[String](lstFromAs("a", "b", "c"), "a")
    assertEquals(a4, null)
    assertEquals(Lst.mkString(b4), "a, b, c")
    val (a5, b5) = Lst.splitBefore[String](lstFromAs("a", "b", "c"), "c")
    assertEquals(Lst.mkString(a5), "a, b")
    assertEquals(Lst.mkString(b5), "c")
    assertEquals(Lst.splitBefore[String](lstFromAs("a", "b", "c"), "x"), null)
    val (a6, b6) = Lst.splitBefore[String](lstFromAs("a", "b", "c", "d", "e", "f"), "d")
    assertEquals(Lst.mkString(a6), "a, b, c")
    assertEquals(Lst.mkString(b6), "d, e, f")
    val ints = (0 until 100).map(_.toString()).toList
    val l7 = lstFromAs(ints: _*)
    for (idx <- 50 to 80) {
      val (a7, b7) = Lst.splitBefore(l7, ints(idx))
      assertEquals(Lst.mkString(a7), (0 until idx).mkString(", "))
      assertEquals(Lst.mkString(b7), (idx until 100).mkString(", "))
    }
  }

  test("ByteStack.splitAt") {
    val bs: Array[Byte] = List[Byte](1, 2, 3, 4).toArray[Byte]
    assert(Try { ByteStack.splitAt(bs, -1) }.isFailure)
    val (a0, b0) = ByteStack.splitAt(bs, 0)
    assertEquals(a0.toList, List[Byte]())
    assertEquals(b0.toList, List[Byte](1, 2, 3, 4))
    val (a1, b1) = ByteStack.splitAt(bs, 1)
    assertEquals(a1.toList, List[Byte](1))
    assertEquals(b1.toList, List[Byte](2, 3, 4))
    val (a3, b3) = ByteStack.splitAt(bs, 3)
    assertEquals(a3.toList, List[Byte](1, 2, 3))
    assertEquals(b3.toList, List[Byte](4))
    val (a4, b4) = ByteStack.splitAt(bs, 4)
    assertEquals(a4.toList, List[Byte](1, 2, 3, 4))
    assertEquals(b4.toList, List[Byte]())
    assert(Try { ByteStack.splitAt(bs, 5) }.isFailure)
    assert(Try { ByteStack.splitAt(bs, 6) }.isFailure)
  }

  test("ByteStack.push") {
    val bs: Array[Byte] = List[Byte](1, 2, 3, 4).toArray[Byte]
    assertEquals(ByteStack.push(bs, 9.toByte).toList, List[Byte](1, 2, 3, 4, 9))
  }
}
