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
        assert(!lm.contains(ref))
        assertEquals(lm.getOrElse(ref, null), null)
        lm = lm.updated(ref, hwd)
        tm = tm.updated(ref, hwd)
        assertEquals(lm.size, tm.size)
        for (h <- tm.valuesIterator) {
          assert(lm.contains(h.address))
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
        assert(lm.contains(ref))
        val oldHwd = lm.getOrElse(ref, null)
        assert(oldHwd ne null)
        assertNotEquals(oldHwd, newHwd)
        val newLm = lm.updated(ref, newHwd)
        assertNotEquals(lm, newLm)
        lm = newLm
        tm = tm.updated(ref, newHwd)
        assertEquals(lm.size, tm.size)
        for (h <- tm.valuesIterator) {
          assert(lm.contains(h.address))
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
      lm = lm.updated(ref, hwd)
      tm = tm.updated(ref, hwd)
    }
    (lm, tm)
  }
}
