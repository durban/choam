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

import java.util.concurrent.ThreadLocalRandom

import scala.util.Random
import scala.collection.immutable.TreeMap

import munit.ScalaCheckSuite

import org.scalacheck.{ Arbitrary, Gen }
import org.scalacheck.Prop.forAll

final class LogMap2Spec extends ScalaCheckSuite { self =>

  implicit def arbMemLoc[A](implicit arbA: Arbitrary[A]): Arbitrary[MemoryLocation[A]] = Arbitrary {
    arbA.arbitrary.flatMap { a =>
      Gen.delay { MemoryLocation.unsafe[A](a) }
    }
  }

  property("insert") {
    forAll { (seed: Long, _refs: Set[MemoryLocation[String]]) =>
      val refs = new Random(seed).shuffle(_refs.toList)
      var lm = LogMap2.empty[String]
      var tm = TreeMap.empty[MemoryLocation[String], LogEntry[String]](
        MemoryLocation.orderingInstance
      )
      for (ref <- refs) {
        val hwd = LogEntry(ref, "x", "y", 0L)
        assertEquals(lm.getOrElseNull(ref.id), null)
        lm = lm.inserted(hwd)
        tm = tm.updated(ref, hwd)
        assertEquals(lm.size, tm.size)
        for (h <- tm.valuesIterator) {
          assertEquals(lm.getOrElseNull(h.address.id), h)
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
        val newHwd = LogEntry(ref, rng.nextString(32), "q", rng.nextLong())
        val oldHwd = lm.getOrElseNull(ref.id)
        assert(oldHwd ne null)
        assertNotEquals(oldHwd, newHwd)
        val newLm = lm.updated(newHwd)
        assertNotEquals(lm, newLm)
        lm = newLm
        tm = tm.updated(ref, newHwd)
        assertEquals(lm.size, tm.size)
        for (h <- tm.valuesIterator) {
          assertEquals(lm.getOrElseNull(h.address.id), h)
        }
      }
    }
  }

  private[this] def lmTmFromRefs[A](
    rng: Random,
    randomA: () => A,
    _refs: Set[MemoryLocation[A]],
  ): (LogMap2[A], TreeMap[MemoryLocation[A], LogEntry[A]]) = {
    val refs = rng.shuffle(_refs.toList)
    var lm = LogMap2.empty[A]
    var tm = TreeMap.empty[MemoryLocation[A], LogEntry[A]](
      MemoryLocation.orderingInstance
    )
    // insert everything:
    for (ref <- refs) {
      val hwd = LogEntry(ref, randomA(), randomA(), rng.nextLong())
      lm = lm.upserted(hwd)
      tm = tm.updated(ref, hwd)
    }
    (lm, tm)
  }

  test("LogMap2#size") {
    val r1 = MemoryLocation.unsafeUnpadded("1")
    val h1 = LogEntry(r1, "1", "x", 42L)
    val r2 = MemoryLocation.unsafeUnpadded("2")
    val h2 = LogEntry(r2, "2", "x", 42L)
    val r3 = MemoryLocation.unsafeUnpadded("3")
    val h31 = LogEntry(r3, "3", "x", 42L)
    val h32 = LogEntry(r3, "3", "y", 42L)
    val r4 = MemoryLocation.unsafeUnpadded("4")
    val h4 = LogEntry(r4, "4", "x", 42L)
    val lm0 = LogMap2.empty[String]
    assertEquals(lm0.size, 0)
    val lm1 = lm0.inserted(h1)
    assertEquals(lm1.size, 1)
    val lm2 = lm1.inserted(h2)
    assertEquals(lm2.size, 2)
    val lm3 = lm2.inserted(h31)
    assertEquals(lm3.size, 3)
    val lm4 = lm3.inserted(h4)
    assertEquals(lm4.size, 4)
    val lm5 = lm4.updated(h32)
    assertEquals(lm5.size, 4)
    val lm1b = lm0.upserted(h31)
    assertEquals(lm1b.size, 1)
    val lm1c = lm1b.upserted(h32)
    assertEquals(lm1c.size, 1)
  }

  test("LogMap2#revalidate") {
    val r1 = MemoryLocation.unsafeUnpadded("1")
    val h1 = LogEntry(r1, "1", "x", 42L)
    val r2 = MemoryLocation.unsafeUnpadded("2")
    val h2 = LogEntry(r2, "2", "x", 42L)
    val lm = LogMap2
      .empty[String]
      .inserted(h1)
      .inserted(h2)
    def mock(r1Version: Long) = new Mcas.UnsealedThreadContext {
      private[mcas] def addVersionCas(desc: Descriptor): Descriptor =
        self.fail("not implemented")
      def impl: Mcas =
        self.fail("not implemented")
      def random: ThreadLocalRandom =
        ThreadLocalRandom.current()
      def readDirect[A](ref: MemoryLocation[A]): A =
        self.fail("not implemented")
      def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] =
        self.fail("not implemented")
      private[mcas] def readVersion[A](ref: MemoryLocation[A]): Long =
        if (ref eq r1) r1Version else 42L // we simulate one of the refs changing version
      def refIdGen: RefIdGen =
        RefIdGen.global
      def start(): Descriptor =
        self.fail("not implemented")
      private[mcas] def tryPerformInternal(desc: Descriptor, optimism: Long): Long =
        self.fail("not implemented")
      def validateAndTryExtend(desc: Descriptor, hwd: LogEntry[_]): Descriptor =
        self.fail("not implemented")
    }

    assert(lm.revalidate(mock(42L)))
    assert(!lm.revalidate(mock(43L)))
  }
}
