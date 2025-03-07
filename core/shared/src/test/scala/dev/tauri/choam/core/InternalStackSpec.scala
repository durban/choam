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
package core

import scala.util.Try

import cats.syntax.all._

final class InternalStackSpec extends BaseSpec {

  test("ArrayObjStack") {
    val s = new ArrayObjStack[String](initSize = 2)
    assert(s.isEmpty())
    assert(!s.nonEmpty())
    assertEquals(s.toListObjStack().toString, "ListObjStack()")
    assertEquals(s.toString, "ArrayObjStack()")
    s.push("a")
    assertEquals(s.toString, "ArrayObjStack(a)")
    assert(!s.isEmpty())
    assert(s.nonEmpty())
    s.push("b")
    assertEquals(s.toString, "ArrayObjStack(b, a)")
    assertSameInstance(s.pop(), "b")
    assertEquals(s.toString, "ArrayObjStack(a)")
    s.push("b")
    assertEquals(s.toString, "ArrayObjStack(b, a)")
    s.pushAll("c" :: "d" :: Nil)
    assertEquals(s.toString, "ArrayObjStack(d, c, b, a)")
    assertEquals(s.peek(), "d")
    assertEquals(s.peek(), "d")
    assertEquals(s.peekSecond(), "c")
    assertEquals(s.peek(), "d")
    assertEquals(s.peekSecond(), "c")
    assertEquals(s.toString, "ArrayObjStack(d, c, b, a)")
    val ls = s.toListObjStack()
    assertSameInstance(s.pop(), "d")
    assertSameInstance(s.pop(), "c")
    assertSameInstance(ls.pop(), "d")
    assertSameInstance(ls.pop(), "c")
    assertEquals(ls.toString, "ListObjStack(b, a)")
    s.push2("c", "d")
    assertSameInstance(s.pop(), "d")
    assertSameInstance(s.pop(), "c")
    s.clear()
    assert(s.isEmpty())
    assert(Try { s.pop() }.isFailure)
  }

  test("ListObjStack") {
    val s = new ListObjStack[String]
    assert(s.isEmpty())
    assert(!s.nonEmpty())
    assertEquals(s.toString, "ListObjStack()")
    val s0 = s.takeSnapshot()
    s.push("a")
    assertEquals(s.toString, "ListObjStack(a)")
    assert(!s.isEmpty())
    assert(s.nonEmpty())
    s.push("b")
    assertEquals(s.toString, "ListObjStack(b, a)")
    assertSameInstance(s.pop(), "b")
    assertEquals(s.toString, "ListObjStack(a)")
    val s1 = s.takeSnapshot()
    s.push("b")
    assertEquals(s.toString, "ListObjStack(b, a)")
    s.pushAll("c" :: "d" :: Nil)
    assertEquals(s.toString, "ListObjStack(d, c, b, a)")
    assertEquals(s.peek(), "d")
    assertEquals(s.peek(), "d")
    assertEquals(s.peekSecond(), "c")
    assertEquals(s.peek(), "d")
    assertEquals(s.peekSecond(), "c")
    assertEquals(s.toString, "ListObjStack(d, c, b, a)")
    val s2 = s.takeSnapshot()
    s.loadSnapshot(s1)
    assertSameInstance(s.pop(), "a")
    assert(s.isEmpty())
    s.loadSnapshot(s2)
    assertSameInstance(s.pop(), "d")
    assertSameInstance(s.pop(), "c")
    s.push2("c", "d")
    assertSameInstance(s.pop(), "d")
    assertSameInstance(s.pop(), "c")
    s.clear()
    assert(s.isEmpty())
    s.loadSnapshot(s0)
    assert(s.isEmpty())
    assert(Try { s.pop() }.isFailure)
  }

  test("ByteStack") {
    val s = new ByteStack(initSize = 2)
    assert(s.isEmpty())
    assert(!s.nonEmpty())
    val s0 = s.takeSnapshot()
    s.push(1.toByte)
    assert(!s.isEmpty())
    assert(s.nonEmpty())
    val s1 = s.takeSnapshot()
    s.push(2.toByte)
    s.push(3.toByte)
    s.push(4.toByte)
    val s2 = s.takeSnapshot()
    s.loadSnapshot(s1)
    assertEquals(s.pop(), 1.toByte)
    assert(s.isEmpty())
    s.loadSnapshot(s2)
    assertEquals(s.pop(), 4.toByte)
    assertEquals(s.pop(), 3.toByte)
    s.push2(3.toByte, 4.toByte)
    assertEquals(s.pop(), 4.toByte)
    assertEquals(s.pop(), 3.toByte)
    s.clear()
    assert(s.isEmpty())
    s.loadSnapshot(s0)
    assert(s.isEmpty())
    assert(Try { s.pop() }.isFailure)
  }

  test("ObjStack.Lst.length") {
    import ListObjStack.Lst
    assertEquals(Lst.length(null), 0)
    assertEquals(Lst.length(Lst(1, null)), 1)
    assertEquals(Lst.length(Lst(1, Lst(2, null))), 2)
    assertEquals(Lst.length(Lst(1, Lst(2, Lst(3, null)))), 3)
    assertEquals(Lst.length(Lst(1, Lst(2, Lst(3, Lst(4, null))))), 4)
  }

  test("ObjStack.Lst.reversed") {
    import ListObjStack.Lst
    assertEquals(Lst.reversed(null), null)
    assertEquals(Lst.reversed(Lst(1, null)).mkString(), "1")
    assertEquals(Lst.reversed(Lst(1, Lst(2, null))).mkString(), "2, 1")
    assertEquals(Lst.reversed(Lst(1, Lst(2, Lst(3, null)))).mkString(), "3, 2, 1")
    assertEquals(Lst.reversed(Lst(1, Lst(2, Lst(3, Lst(4, null))))).mkString(), "4, 3, 2, 1")
  }

  test("ObjStack.Lst.concat") {
    import ListObjStack.Lst
    assertEquals(Lst.concat(null, null), null)
    assertEquals(Lst.concat(Lst(1, null), null).mkString(), "1")
    assertEquals(Lst.concat(null, Lst(1, null)).mkString(), "1")
    assertEquals(Lst.concat(Lst(1, null), Lst(2, null)).mkString(), "1, 2")
    assertEquals(Lst.concat(Lst(1, null), Lst(2, Lst(3, null))).mkString(), "1, 2, 3")
    assertEquals(Lst.concat(Lst(1, Lst(2, null)), Lst(3, null)).mkString(), "1, 2, 3")
    assertEquals(Lst.concat(Lst(1, Lst(2, null)), Lst(3, Lst(4, null))).mkString(), "1, 2, 3, 4")
    assertEquals(Lst.concat(Lst(1, Lst(2, null)), null).mkString(), "1, 2")
    assertEquals(Lst.concat(null, Lst(3, Lst(4, null))).mkString(), "3, 4")
  }

  test("ObjStack.Lst.splitBefore") {
    import ListObjStack.Lst
    assertEquals(Lst.splitBefore[String](null, "a"), null)
    assertEquals(Lst.splitBefore[String](Lst("x", null), "a"), null)
    assertEquals(Lst.splitBefore[String](Lst("x", Lst("y", null)), "a"), null)
    val (a0, b0) = Lst.splitBefore[String](Lst("a", null), "a")
    assertEquals(a0, null)
    assertEquals(b0.mkString(), "a")
    val (a1, b1) = Lst.splitBefore[String](Lst("a", Lst("b", null)), "a")
    assertEquals(a1, null)
    assertEquals(b1.mkString(), "a, b")
    val (a2, b2) = Lst.splitBefore[String](Lst("a", Lst("b", null)), "b")
    assertEquals(a2.mkString(), "a")
    assertEquals(b2.mkString(), "b")
    val (a3, b3) = Lst.splitBefore[String](Lst("a", Lst("b", Lst("c", null))), "b")
    assertEquals(a3.mkString(), "a")
    assertEquals(b3.mkString(), "b, c")
    val (a4, b4) = Lst.splitBefore[String](Lst("a", Lst("b", Lst("c", null))), "a")
    assertEquals(a4, null)
    assertEquals(b4.mkString(), "a, b, c")
    val (a5, b5) = Lst.splitBefore[String](Lst("a", Lst("b", Lst("c", null))), "c")
    assertEquals(a5.mkString(), "a, b")
    assertEquals(b5.mkString(), "c")
    assertEquals(Lst.splitBefore[String](Lst("a", Lst("b", Lst("c", null))), "x"), null)
    val (a6, b6) = Lst.splitBefore[String](Lst("a", Lst("b", Lst("c", Lst("d", Lst("e", Lst("f", null)))))), "d")
    assertEquals(a6.mkString(), "a, b, c")
    assertEquals(b6.mkString(), "d, e, f")
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

  test("ByteStack nextPowerOf2 underflow") {
    val bs = new ByteStack(8)
    bs.loadSnapshot(new Array[Byte](0))
    assert(Either.catchOnly[NoSuchElementException] { bs.pop() }.isLeft)
    bs.push(42.toByte)
    assertEquals(bs.pop(), 42.toByte)
  }

  test("ByteStack nextPowerOf2 overflow") {
    val bs = new ByteStack(initSize = 1 << 30)
    var idx = 0
    while (idx < (1 << 30)) {
      bs.push(42.toByte)
      idx += 1
    }
    if (this.isJs()) {
      assert(Either.catchOnly[AssertionError] { bs.push(99.toByte) }.isLeft)
    } else {
      assert(Either.catchOnly[NegativeArraySizeException] { bs.push(99.toByte) }.isLeft)
    }
  }

  test("ArrayObjStack nextPowerOf2 overflow") {
    assume(!this.isJs()) // JavaScript heap out of memory
    val bs = new ArrayObjStack[String](initSize = 1 << 30)
    var idx = 0
    while (idx < (1 << 30)) {
      bs.push("")
      idx += 1
    }
    assert(Either.catchOnly[NegativeArraySizeException] { bs.push("err") }.isLeft)
  }
}
