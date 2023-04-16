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
package skiplist

import scala.collection.mutable.{ TreeMap => MutTreeMap }

import cats.kernel.Order

import munit.ScalaCheckSuite
import org.scalacheck.Prop

final class SkipListSpec extends ScalaCheckSuite {

  import SkipListSpec._

  test("put / get") {
    val m = new SkipListMap[Int, String]
    assertEquals(m.toString, "SkipListMap()")
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), None)
    assertEquals(m.get(-1), None)
    assertEquals(m.get(Int.MaxValue), None)
    assertEquals(m.get(Int.MinValue), None)

    m.put(1, "A")
    assertEquals(m.toString, "SkipListMap(...)")
    assertEquals(m.get(0), None)
    assertEquals(m.get(-1), None)
    assertEquals(m.get(2), None)
    assertEquals(m.get(1), Some("A"))

    m.put(42, "X")
    assertEquals(m.get(1), Some("A"))
    assertEquals(m.get(42), Some("X"))
  }

  test("put / del") {
    val m = new SkipListMap[Int, String]
    m.put(0, "0")
    m.put(1, "1")
    m.put(5, "5")
    m.put(4, "4")
    m.put(2, "2")
    m.put(3, "3")
    assertEquals(m.get(0), Some("0"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), Some("4"))
    assertEquals(m.get(5), Some("5"))

    assert(!m.del(-1))
    assert(!m.del(6))
    assert(m.del(4))
    assertEquals(m.get(0), Some("0"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), Some("5"))

    assert(!m.del(4))
    assert(m.del(0))
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), Some("5"))

    assert(!m.del(4))
    assert(!m.del(0))
    assert(m.del(5))
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), None)

    assert(!m.del(4))
    assert(!m.del(0))
    assert(!m.del(5))
    assert(m.del(2))
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), None)
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), None)
  }

  property("empty list get") {
    val m = new SkipListMap[Int, String]
    Prop.forAll { (k: Int) =>
      assert(m.get(k).isEmpty)
    }
  }

  property("put then get") {
    val m = new SkipListMap[Int, String]
    Prop.forAll { (k: Int, v: String) =>
      m.put(k, v)
      assertEquals(m.get(k), Some(v))
    }
  }

  property("put then del") {
    val m = new SkipListMap[Int, String]
    Prop.forAll { (k: Int, v: String) =>
      m.put(k, v)
      m.del(k)
      assert(m.get(k).isEmpty)
    }
  }

  property("random put / del / get") {
    Prop.forAll { (put: List[Int], del: List[Int]) =>
      val m = new SkipListMap[Int, String]
      val s = new Shadow[Int, String]
      for (k <- put) {
        assertEquals(m.put(k, k.toString), s.put(k, k.toString))
      }
      for (k <- del) {
        assertEquals(m.del(k), s.del(k))
      }
      for (k <- put) {
        assertEquals(m.get(k), s.get(k))
      }
      for (k <- del) {
        assert(m.get(k).isEmpty)
      }
    }
  }
}

final object SkipListSpec {

  final class Shadow[K, V] private (map: MutTreeMap[K, V])(implicit K: Order[K]) {

    def this()(implicit K: Order[K]) = {
      this(MutTreeMap.empty(K.toOrdering))
    }

    final def put(key: K, value: V): Option[V] = {
      val old = map.get(key)
      map.put(key, value)
      old
    }

    final def get(key: K): Option[V] = {
      map.get(key)
    }

    final def del(key: K): Boolean = {
      map.remove(key).isDefined
    }

    final def size(): Int = {
      map.size
    }
  }
}
