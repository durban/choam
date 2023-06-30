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
package internal
package skiplist

import scala.collection.mutable.{ TreeMap => MutTreeMap }

import cats.kernel.Order

import munit.ScalaCheckSuite
import org.scalacheck.Prop

import SkipListHelper.{ listFromSkipList, listFromSkipListIterator }

final class SkipListSpec extends ScalaCheckSuite with SkipListHelper {

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

  test("put / remove") {
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

    assert(!m.remove(-1, "x"))
    assert(!m.remove(6, "x"))
    assert(!m.remove(4, "x"))
    assertEquals(m.get(4), Some("4"))
    assert(m.remove(4, "4"))
    assertEquals(m.get(0), Some("0"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), Some("5"))

    assert(!m.remove(4, "4"))
    assert(!m.remove(0, "x"))
    assertEquals(m.get(0), Some("0"))
    assert(m.remove(0, "0"))
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), Some("5"))

    assert(!m.remove(4, "4"))
    assert(!m.remove(0, "0"))
    assert(!m.remove(5, "x"))
    assertEquals(m.get(5), Some("5"))
    assert(m.remove(5, "5"))
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), None)

    assert(!m.remove(4, "4"))
    assert(!m.remove(0, "0"))
    assert(!m.remove(5, "5"))
    assert(!m.remove(2, "x"))
    assertEquals(m.get(2), Some("2"))
    assert(m.remove(2, "2"))
    assertEquals(m.get(0), None)
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), None)
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), None)
    assertEquals(m.get(5), None)
  }

  test("put / replace") {
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

    assert(!m.replace(-1, "x", "y"))
    assert(!m.replace(6, "x", "y"))
    assert(!m.replace(4, "x", "y"))
    assertEquals(m.get(4), Some("4"))
    assert(m.replace(4, "4", "4!"))
    assertEquals(m.get(0), Some("0"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), Some("4!"))
    assertEquals(m.get(5), Some("5"))

    assert(!m.replace(4, "4", "y"))
    assert(!m.replace(0, "x", "y"))
    assertEquals(m.get(0), Some("0"))
    assert(m.replace(0, "0", "0!"))
    assertEquals(m.get(0), Some("0!"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), Some("4!"))
    assertEquals(m.get(5), Some("5"))

    assert(!m.replace(4, "4", "y"))
    assert(!m.replace(0, "0", "y"))
    assert(!m.replace(5, "x", "y"))
    assertEquals(m.get(5), Some("5"))
    assert(m.replace(5, "5", "5!"))
    assertEquals(m.get(0), Some("0!"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), Some("4!"))
    assertEquals(m.get(5), Some("5!"))

    assert(!m.replace(4, "4", "x"))
    assert(!m.replace(0, "0", "x"))
    assert(!m.replace(5, "5", "x"))
    assert(!m.replace(2, "x", "y"))
    assertEquals(m.get(2), Some("2"))
    assert(m.replace(2, "2", "2!"))
    assertEquals(m.get(0), Some("0!"))
    assertEquals(m.get(1), Some("1"))
    assertEquals(m.get(2), Some("2!"))
    assertEquals(m.get(3), Some("3"))
    assertEquals(m.get(4), Some("4!"))
    assertEquals(m.get(5), Some("5!"))
  }

  test("putIfAbsent") {
    val m = new SkipListMap[Int, String]
    assertEquals(m.putIfAbsent(42, "foo"), None)
    assertEquals(m.get(42), Some("foo"))
    assertEquals(m.putIfAbsent(42, "bar"), Some("foo"))
    assertEquals(m.get(42), Some("foo"))
    assertEquals(m.putIfAbsent(42, "xyz"), Some("foo"))
    assertEquals(m.get(42), Some("foo"))
    assertEquals(m.putIfAbsent(33, "xyz"), None)
    assertEquals(m.get(42), Some("foo"))
    assertEquals(m.get(33), Some("xyz"))
  }

  test("foreach / iterator") {
    val m = new SkipListMap[Int, String]
    assertEquals(listFromSkipList(m), List())
    assertEquals(listFromSkipListIterator(m), List())

    m.put(99, "A")
    m.put(-1, "B")
    m.put(100, "C")
    val exp1 = List(-1 -> "B", 99 -> "A", 100 -> "C")
    assertEquals(listFromSkipList(m), exp1)
    assertEquals(listFromSkipListIterator(m), exp1)

    m.del(99)
    val exp2 = List(-1 -> "B", 100 -> "C")
    assertEquals(listFromSkipList(m), exp2)
    assertEquals(listFromSkipListIterator(m), exp2)

    m.put(100, "X")
    val exp3 = List(-1 -> "B", 100 -> "X")
    assertEquals(listFromSkipList(m), exp3)
    assertEquals(listFromSkipListIterator(m), exp3)

    m.del(-1)
    m.del(100)
    assertEquals(listFromSkipList(m), List())
    assertEquals(listFromSkipListIterator(m), List())
  }

  property("empty list get") {
    val m = new SkipListMap[Int, String]
    Prop.forAll { (k: Int) =>
      assert(m.get(k).isEmpty)
    }
  }

  property("put then get") {
    Prop.forAll { (m: SkipListMap[Int, String], k: Int, v: String) =>
      m.put(k, v)
      assertEquals(m.get(k), Some(v))
    }
  }

  property("put then del") {
    Prop.forAll { (m: SkipListMap[Int, String], k: Int, v: String) =>
      m.put(k, v)
      m.del(k)
      assertEquals(m.get(k), None)
    }
  }

  property("put then remove") {
    Prop.forAll { (m: SkipListMap[Int, String], k: Int, v: String) =>
      m.put(k, v)
      m.remove(k, v + "!")
      assertEquals(m.get(k), Some(v))
      m.remove(k, v)
      assertEquals(m.get(k), None)
    }
  }

  property("put then replace") {
    Prop.forAll { (m: SkipListMap[Int, String], k: Int, v: String) =>
      m.put(k, v)
      assert(!m.replace(k, v + "!", "foo"))
      assertEquals(m.get(k), Some(v))
      assert(m.replace(k, v, "foo"))
      assertEquals(m.get(k), Some("foo"))
    }
  }

  property("putIfAbsent doesn't overwrite values") {
    Prop.forAll { (ks: Set[Int], k: Int) =>
      val m = new SkipListMap[Int, String]
      for (k <- ks) {
        m.put(k, k.toString)
      }
      val v = "xyz"
      if (ks.contains(k)) {
        assertEquals(m.putIfAbsent(k, v), Some(k.toString))
        assertEquals(m.get(k), Some(k.toString))
      } else {
        assertEquals(m.putIfAbsent(k, v), None)
        assertEquals(m.get(k), Some(v))
      }
    }
  }

  property("foreach returns each KV-pair") {
    Prop.forAll { (_ks: Set[Int], k: Int) =>
      val ks = (_ks + k).toList.sorted
      val m = new SkipListMap[Int, String]
      for (k <- ks) {
        m.put(k, k.toString)
      }
      assert(m.del(k))
      assertEquals(listFromSkipList(m), (_ks - k).toList.sorted.map(k => k -> k.toString))
    }
  }

  property("random put / del / remove / replace / get") {
    Prop.forAll { (put: List[Int], del: List[Int], remove: List[(Int, String)], replace: List[(Int, String, String)]) =>
      val m = new SkipListMap[Int, String]
      val s = new Shadow[Int, String]
      def checkAllKeys(): Unit = {
        for (k <- (put ++ del ++ remove.map(_._1) ++ replace.map(_._1))) {
          assertEquals(m.get(k), s.get(k))
        }
      }
      for (k <- put) {
        assertEquals(m.put(k, k.toString), s.put(k, k.toString))
      }
      checkAllKeys()
      for (k <- del) {
        assertEquals(m.del(k), s.del(k))
      }
      checkAllKeys()
      for ((k, v) <- remove) {
        assertEquals(m.remove(k, v), s.remove(k, v))
      }
      checkAllKeys()
      for ((k, ov, nv) <- replace) {
        assertEquals(m.replace(k, ov, nv), s.replace(k, ov, nv))
      }
      checkAllKeys()
    }
  }

  property("null keys/values") {
    val nullTolerantStringOrder: Order[String] = { (x, y) =>
      if ((x eq null) && (y eq null)) {
        0
      } else if (x eq null) {
        -1
      } else if (y eq null) {
        1
      } else {
        Order[String].compare(x, y)
      }
    }
    Prop.forAll { (kvs: Map[String, String]) =>
      val m = new SkipListMap[String, String]()(nullTolerantStringOrder)
      for ((k, v) <- kvs) {
        m.put(k, v)
      }
      def checkOriginalItems(ignoreKeys: Set[String]): Unit = {
        for ((k, v) <- kvs if (!ignoreKeys.contains(k))) {
          assertEquals(m.get(k), Some(v))
        }
      }
      // null key:
      m.put(null, "a")
      checkOriginalItems(Set(null))
      assertEquals(m.get(null), Some("a"))
      assertEquals(m.putIfAbsent(null, "x"), Some("a"))
      assertEquals(m.get(null), Some("a"))
      assert(m.del(null))
      assertEquals(m.get(null), None)
      assertEquals(m.putIfAbsent(null, "a"), None)
      assertEquals(m.get(null), Some("a"))
      checkOriginalItems(Set(null))
      assert(!m.remove(null, "x"))
      assert(m.remove(null, "a"))
      checkOriginalItems(Set(null))
      assertEquals(m.get(null), None)
      assertEquals(m.putIfAbsent(null, "a"), None)
      assertEquals(m.get(null), Some("a"))
      // null value:
      val myKey = "qwerty"
      m.put(myKey, null)
      checkOriginalItems(Set(null, myKey))
      assertEquals(m.get(null), Some("a"))
      assertEquals(m.get(myKey), Some(null))
      if (kvs.nonEmpty) {
        val arbKey = kvs.keysIterator.next()
        val itsValue = kvs(arbKey)
        assertEquals(m.putIfAbsent(arbKey, null), Some(itsValue))
        assertEquals(m.get(arbKey), Some(itsValue))
      }
      assert(!m.remove(myKey, "x"))
      assertEquals(m.get(myKey), Some(null))
      assert(m.remove(myKey, null))
      assertEquals(m.get(myKey), None)
      checkOriginalItems(Set(null, myKey))
      assertEquals(m.putIfAbsent(myKey, null), None)
      assertEquals(m.get(myKey), Some(null))
      checkOriginalItems(Set(null, myKey))
      // null key and value:
      m.put(null, null)
      checkOriginalItems(Set(null, myKey))
      assertEquals(m.get(null), Some(null))
      assertEquals(m.get(myKey), Some(null))
      assert(!m.remove(null, "x"))
      assertEquals(m.get(null), Some(null))
      assert(m.remove(null, null))
      assertEquals(m.get(null), None)
      checkOriginalItems(Set(null, myKey))
      assertEquals(m.putIfAbsent(null, null), None)
      assertEquals(m.get(null), Some(null))
      checkOriginalItems(Set(null, myKey))
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

    final def remove(key: K, value: V): Boolean = {
      map.get(key) match {
        case Some(curr) =>
          if (equ(curr, value)) {
            map.remove(key)
            true
          } else {
            false
          }
        case None =>
          false
      }
    }

    final def replace(key: K, ov: V, nv: V): Boolean = {
      map.get(key) match {
        case Some(curr) =>
          if (equ(curr, ov)) {
            map.put(key, nv)
            true
          } else {
            false
          }
        case None =>
          false
      }
    }

    final def size(): Int = {
      map.size
    }
  }
}
