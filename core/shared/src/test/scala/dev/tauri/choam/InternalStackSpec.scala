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

  test("ObjStack.Lst.reversed") {
    import ObjStack.Lst
    assertEquals(Lst.reversed(null), null)
    assertEquals(Lst.reversed(Lst(1, null)).mkString(), "1")
    assertEquals(Lst.reversed(Lst(1, Lst(2, null))).mkString(), "2, 1")
    assertEquals(Lst.reversed(Lst(1, Lst(2, Lst(3, null)))).mkString(), "3, 2, 1")
    assertEquals(Lst.reversed(Lst(1, Lst(2, Lst(3, Lst(4, null))))).mkString(), "4, 3, 2, 1")
  }

  test("ObjStack.Lst.concat") {
    import ObjStack.Lst
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
}
