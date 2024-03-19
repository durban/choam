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
package internal
package mcas

import scala.util.Random
import scala.collection.immutable.TreeMap

import munit.ScalaCheckSuite

import org.scalacheck.{ Arbitrary, Gen }
import org.scalacheck.Prop.forAll

final class LogMapSpec extends ScalaCheckSuite {

  implicit def arbMemLoc[A](implicit arbA: Arbitrary[A]): Arbitrary[MemoryLocation[A]] = Arbitrary {
    arbA.arbitrary.flatMap { a =>
      Gen.delay { MemoryLocation.unsafe[A](a) }
    }
  }

  property("insert") {
    forAll { (seed: Long, _refs: Set[MemoryLocation[String]]) =>
      val refs = new Random(seed).shuffle(_refs.toList)
      var lm = LogMap.empty
      var tm = TreeMap.empty[MemoryLocation[String], HalfWordDescriptor[String]](
        MemoryLocation.orderingInstance
      )
      for (ref <- refs) {
        val hwd = HalfWordDescriptor(ref, "x", "y", 0L)
        assertEquals(lm.getOrElse(ref, null), null)
        lm = lm.inserted(ref, hwd)
        tm = tm.updated(ref, hwd)
        assertEquals(lm.size, tm.size)
        for (h <- tm.valuesIterator) {
          assertEquals(lm.getOrElse(h.address, null), h)
        }
      }
    }
  }

  property("overwrite") {
    forAll { (seed: Long, _refs: Set[MemoryLocation[String]]) =>
      val rng = new Random(seed)
      var (lm, tm) = lmTmFromRefs(rng, () => rng.nextString(32), _refs)
      // overwrite everything:
      val shuffled = rng.shuffle(tm.keySet.toList)
      for (ref <- shuffled) {
        val newHwd = HalfWordDescriptor(ref, rng.nextString(32), "q", rng.nextLong())
        val oldHwd = lm.getOrElse(ref, null)
        assert(oldHwd ne null)
        assertNotEquals(oldHwd, newHwd)
        val newLm = lm.updated(ref, newHwd)
        assertNotEquals(lm, newLm)
        lm = newLm
        tm = tm.updated(ref, newHwd)
        assertEquals(lm.size, tm.size)
        for (h <- tm.valuesIterator) {
          assertEquals(lm.getOrElse(h.address, null), h)
        }
      }
    }
  }

  property("isDisjoint") {
    forAll { (seed: Long, _refs1: Set[MemoryLocation[String]], _refs2: Set[MemoryLocation[String]]) =>
      val rng = new Random(seed)
      val (lm1, tm1) = lmTmFromRefs(rng, () => rng.nextString(32), _refs1)
      val (lm2, tm2) = lmTmFromRefs(rng, () => rng.nextString(32), _refs2)
      val exp = tm1.keySet.intersect(tm2.keySet).isEmpty
      assertEquals(
        lm1.isDisjoint(lm2),
        exp,
      )
      assertEquals(
        lm2.isDisjoint(lm1),
        exp,
      )
    }
  }

  private[this] def lmTmFromRefs[A](
    rng: Random,
    randomA: () => A,
    _refs: Set[MemoryLocation[A]],
  ): (LogMap, TreeMap[MemoryLocation[A], HalfWordDescriptor[A]]) = {
    val refs = rng.shuffle(_refs.toList)
    var lm = LogMap.empty
    var tm = TreeMap.empty[MemoryLocation[A], HalfWordDescriptor[A]](
      MemoryLocation.orderingInstance
    )
    // insert everything:
    for (ref <- refs) {
      val hwd = HalfWordDescriptor(ref, randomA(), randomA(), rng.nextLong())
      lm = lm.upserted(ref, hwd)
      tm = tm.updated(ref, hwd)
    }
    (lm, tm)
  }

  test("LogMap#size") {
    val r1 = MemoryLocation.unsafeUnpadded("1")
    val h1 = HalfWordDescriptor(r1, "1", "x", 42L)
    val r2 = MemoryLocation.unsafeUnpadded("2")
    val h2 = HalfWordDescriptor(r2, "2", "x", 42L)
    val r3 = MemoryLocation.unsafeUnpadded("3")
    val h31 = HalfWordDescriptor(r3, "3", "x", 42L)
    val h32 = HalfWordDescriptor(r3, "3", "y", 42L)
    val r4 = MemoryLocation.unsafeUnpadded("4")
    val h4 = HalfWordDescriptor(r4, "4", "x", 42L)
    val lm0 = LogMap.empty
    assertEquals(lm0.size, 0)
    val lm1 = lm0.inserted(r1, h1)
    assertEquals(lm1.size, 1)
    val lm2 = lm1.inserted(r2, h2)
    assertEquals(lm2.size, 2)
    val lm3 = lm2.inserted(r3, h31)
    assertEquals(lm3.size, 3)
    val lm4 = lm3.inserted(r4, h4)
    assertEquals(lm4.size, 4)
    val lm5 = lm4.updated(r3, h32)
    assertEquals(lm5.size, 4)
    val lm1b = lm0.upserted(r3, h31)
    assertEquals(lm1b.size, 1)
    val lm1c = lm1b.upserted(r3, h32)
    assertEquals(lm1c.size, 1)
  }
}
