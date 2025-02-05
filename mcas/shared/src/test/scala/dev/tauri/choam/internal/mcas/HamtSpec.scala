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
package internal
package mcas

import java.util.concurrent.ThreadLocalRandom

import scala.util.Random
import scala.collection.immutable.LongMap

import cats.syntax.all._

import munit.ScalaCheckSuite

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

final class HamtSpec extends ScalaCheckSuite with MUnitUtils with PropertyHelpers {

  import HamtSpec.{ LongHamt, LongWr, Val, SpecVal, hamtFromList, addAll }

  override protected def scalaCheckTestParameters: org.scalacheck.Test.Parameters = {
    val p = super.scalaCheckTestParameters
    p.withMaxSize(p.maxSize * (if (isJvm()) 32 else 2))
  }

  test("Val/SpecVal") {
    assert(Val(42L) == Val(42L))
    assert(Val(42L) != Val(99L))
    val sv = new SpecVal(42L)
    assert(sv == sv)
    assert(sv != Val(42L))
    assert(Val(42L) != sv)
    val sv2 = new SpecVal(42L)
    assert(sv != sv2)
    assertEquals(sv.##, Val(42L).##)
    assertEquals(sv2.##, Val(42L).##)
  }

  property("HAMT logicalIdx") {
    val h = LongHamt.empty

    def testLogicalIdx(n: Long): Unit = {
      assertEquals(h.logicalIdx_public(n, shift =  0), ( n >>> 58             ).toInt)
      assertEquals(h.logicalIdx_public(n, shift =  6), ((n >>> 52) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 12), ((n >>> 46) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 18), ((n >>> 40) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 24), ((n >>> 34) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 30), ((n >>> 28) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 36), ((n >>> 22) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 42), ((n >>> 16) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 48), ((n >>> 10) & 63L      ).toInt)
      assertEquals(h.logicalIdx_public(n, shift = 54), ((n >>>  4) & 63L      ).toInt)
      // this is the tricky one, the last level:
      assertEquals(h.logicalIdx_public(n, shift = 60), ((n         & 15L) << 2).toInt)
    }

    val prop1 = forAll(Gen.choose(Long.MinValue, Long.MaxValue)) { (n: Long) =>
      testLogicalIdx(n)
    }

    val prop2 = forAll { (n: Long) =>
      testLogicalIdx(n)
    }

    prop1 && prop2
  }

  test("HAMT examples") {
    val c0 = 0x0L
    val c1 = 0x8000000000000000L
    val c2 = 0x4000000000000000L
    val c3 = 0x1L
    val c4 = 0x2L
    val h0 = LongHamt.empty
    assertEquals(h0.size, 0)
    assertEquals(h0.toArray.toList, List.empty[Val])
    assertEquals(h0.getOrElse(c0, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(c1, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(c2, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(c4, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h0.updated(Val(c0))).isLeft)
    val h1 = h0.inserted(Val(c0))
    assertEquals(h1.size, 1)
    assertEquals(h1.toArray.toList, List(c0).map(Val(_)))
    assertEquals(h1.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h1.getOrElse(c1, Val(42L)), Val(42L))
    assertEquals(h1.getOrElse(c2, Val(42L)), Val(42L))
    assertEquals(h1.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h1.getOrElse(c4, Val(42L)), Val(42L))
    val nv = Val(c0)
    val h1b = h1.updated(nv)
    assertEquals(h1b.size, h1.size)
    assert(h1b.getOrElse(c0, Val(42L)) eq nv)
    assert(Either.catchOnly[IllegalArgumentException](h1.updated(Val(c1))).isLeft)
    val h2 = h1.inserted(Val(c1))
    assertEquals(h2.size, 2)
    assertEquals(h2.toArray.toList, List(c0, c1).map(Val(_)))
    assertEquals(h2.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h2.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h2.getOrElse(c2, Val(42L)), Val(42L))
    assertEquals(h2.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h2.getOrElse(c4, Val(42L)), Val(42L))
    val nnv = Val(c0)
    val h2b = h2.upserted(nnv)
    assertEquals(h2b.size, h2.size)
    assertEquals(h2b.toArray.toList, List(c0, c1).map(Val(_)))
    assert(h2b.getOrElse(c0, Val(42L)) eq nnv)
    assert(h2b.getOrElse(c0, Val(42L)) ne nv)
    assert(Either.catchOnly[IllegalArgumentException](h2.updated(Val(c2))).isLeft)
    val h3 = h2.upserted(Val(c2))
    assertEquals(h3.size, 3)
    assertEquals(h3.toArray.toList, List(c0, c2, c1).map(Val(_)))
    assertEquals(h3.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h3.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h3.getOrElse(c2, Val(42L)), Val(c2))
    assertEquals(h3.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h3.getOrElse(c4, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h3.inserted(Val(c2))).isLeft)
    val h4 = h3.inserted(Val(c3)) // collides with c0 at 1st level
    assertEquals(h4.size, 4)
    assertEquals(h4.toArray.toList, List(c0, c3, c2, c1).map(Val(_)))
    assertEquals(h4.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h4.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h4.getOrElse(c2, Val(42L)), Val(c2))
    assertEquals(h4.getOrElse(c3, Val(42L)), Val(c3))
    assertEquals(h4.getOrElse(c4, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h4.updated(Val(99L))).isLeft)
    val h5 = h4.inserted(Val(c4))
    assertEquals(h5.size, 5)
    assertEquals(h5.toArray.toList, List(c0, c3, c4, c2, c1).map(Val(_)))
    assertEquals(h5.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h5.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h5.getOrElse(c2, Val(42L)), Val(c2))
    assertEquals(h5.getOrElse(c3, Val(42L)), Val(c3))
    assertEquals(h5.getOrElse(c4, Val(42L)), Val(c4))
    // removal:
    val h6: LongHamt = h5.removed(LongWr(c4))
    assertEquals(h6.size, 4)
    assertEquals(h6.toArray.toList, List(c0, c3, c2, c1).map(Val(_)))
    assertEquals(h6, h4)
    assertEquals(h6, h6)
    val h6b = h6.removed(LongWr(c4))
    assertSameInstance(h6b, h6)
    val h7 = h6.removed(LongWr(c1))
    assertEquals(h7.size, 3)
    assertEquals(h7.toArray.toList, List(c0, c3, c2).map(Val(_)))
    val h8 = h7.removed(LongWr(c2))
    assertEquals(h8.size, 2)
    assertEquals(h8.toArray.toList, List(c0, c3).map(Val(_)))
    val h9 = h8.inserted(Val(c1))
    assertEquals(h9.size, 3)
    assertEquals(h9.toArray.toList, List(c0, c3, c1).map(Val(_)))
  }

  property("HAMT lookup/upsert/toArray (default generator)") {
    forAll { (seed: Long, _nums: Set[Long]) =>
      testBasics(seed, _nums)
    }
  }

  property("HAMT lookup/upsert/toArray (RIG generator)") {
    myForAll { (seed: Long, _nums: Set[Long]) =>
      testBasics(seed, _nums)
    }
  }

  private def testBasics(seed: Long, _nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val nums = rng.shuffle(_nums.toList)
    var hamt = LongHamt.empty
    var shadow = LongMap.empty[Val]
    for (n <- nums) {
      val v = Val(n)
      assert(Either.catchOnly[IllegalArgumentException](hamt.updated(v)).isLeft)
      hamt = if (rng.nextBoolean()) {
        hamt.inserted(v)
      } else {
        hamt.upserted(v)
      }
      shadow = shadow.updated(n, v)
      assert(hamt.getOrElse(n, null) eq v)
      assertSameMaps(hamt, shadow)
    }
    for (n <- rng.shuffle(nums)) {
      val nv = Val(n)
      assert(Either.catchOnly[IllegalArgumentException](hamt.inserted(nv)).isLeft)
      hamt = if (rng.nextBoolean()) {
        hamt.updated(nv)
      } else {
        hamt.upserted(nv)
      }
      shadow = shadow.updated(n, nv)
      assert(hamt.getOrElse(n, null) eq nv)
      assertSameMaps(hamt, shadow)
    }
  }

  property("HAMT computeIfAbsent (default generator)") {
    forAll { (seed: Long, _nums: Set[Long]) =>
      testComputeIfAbsent(seed, _nums)
    }
  }

  property("HAMT computeIfAbsent (RIG generator)") {
    myForAll { (seed: Long, _nums: Set[Long]) =>
      testComputeIfAbsent(seed, _nums)
    }
  }

  property("HAMT computeOrModify (default generator)") {
    forAll { (seed: Long, _nums: Set[Long]) =>
      testComputeOrModify(seed, _nums)
    }
  }

  property("HAMT computeOrModify (RIG generator)") {
    myForAll { (seed: Long, _nums: Set[Long]) =>
      testComputeOrModify(seed, _nums)
    }
  }

  private def testComputeIfAbsent(seed: Long, _nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val nums = rng.shuffle(_nums.toList)
    var hamt = LongHamt.empty
    val token1 = new AnyRef
    for (n <- nums) {
      val v = Val(n)
      var count = 0
      val nullVis = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val =
          fail("present called")
        override def entryAbsent(k: LongWr, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token1)
          count += 1
          null
        }
      }
      val newHamt = hamt.computeIfAbsent(LongWr(n), token1, nullVis)
      assertEquals(count, 1)
      assertSameInstance(newHamt, hamt)
      val token2 = new AnyRef
      val vis = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val =
          fail("present called")
        override def entryAbsent(k: LongWr, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token2)
          count += 1
          v
        }
      }
      hamt = hamt.computeIfAbsent(LongWr(n), token2, vis)
      assertEquals(count, 2)
      assertEquals(hamt.getOrElse(n, null), v)
    }
    for (n <- rng.shuffle(nums)) {
      var e: Val = null
      var count = 0
      val token3 = new AnyRef
      val incorrectVisitor = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token3)
          count += 1
          e = a
          new Val(a.value) // same value, different instance
        }
        override def entryAbsent(k: LongWr, tok: AnyRef): Val =
          fail("absent called")
      }
      assert(Either.catchOnly[AssertionError] {
        hamt.computeIfAbsent(LongWr(n), token3, incorrectVisitor)
      }.isLeft)
      val vis = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token3)
          count += 1
          e = a
          a
        }
        override def entryAbsent(k: LongWr, tok: AnyRef): Val =
          fail("absent called")
      }
      val newHamt = hamt.computeIfAbsent(LongWr(n), token3, vis)
      assertEquals(count, 2)
      assertEquals(e, Val(n))
      assertSameInstance(newHamt, hamt)
    }
  }

  private def testComputeOrModify(seed: Long, _nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val nums = rng.shuffle(_nums.toList)
    var hamt = LongHamt.empty
    val token1 = new AnyRef
    for (n <- nums) {
      val v = Val(n)
      var count = 0
      val nullVis = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val =
          fail("present called")
        override def entryAbsent(k: LongWr, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token1)
          count += 1
          null
        }
      }
      val newHamt = hamt.computeOrModify(LongWr(n), token1, nullVis)
      assertEquals(count, 1)
      assertSameInstance(newHamt, hamt)
      val token2 = new AnyRef
      val vis = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val =
          fail("present called")
        override def entryAbsent(k: LongWr, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token2)
          count += 1
          v
        }
      }
      hamt = hamt.computeOrModify(LongWr(n), token2, vis)
      assertEquals(count, 2)
      assertEquals(hamt.getOrElse(n, null), v)
    }
    for (n <- rng.shuffle(nums)) {
      var e: Val = null
      var count = 0
      val token3 = new AnyRef
      val readOnlyVisitor = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token3)
          count += 1
          e = a
          a
        }
        override def entryAbsent(k: LongWr, tok: AnyRef): Val =
          fail("absent called")
      }
      val newHamt2 = hamt.computeOrModify(LongWr(n), token3, readOnlyVisitor)
      assertSameInstance(newHamt2, hamt)
      val vis = new Hamt.EntryVisitor[LongWr, Val, AnyRef] {
        override def entryPresent(k: LongWr, a: Val, tok: AnyRef): Val = {
          assertEquals(k.n, n)
          assertSameInstance(tok, token3)
          count += 1
          val newA = Val(a.value, extra = "foo")
          e = newA
          newA
        }
        override def entryAbsent(k: LongWr, tok: AnyRef): Val =
          fail("absent called")
      }
      val newHamt = hamt.computeOrModify(LongWr(n), token3, vis)
      assertEquals(count, 2)
      assertEquals(e, Val(n, "foo"))
      assert(newHamt ne hamt)
      assertEquals(newHamt.getOrElse(n, Val(42L)), Val(n, "foo"))
      hamt = newHamt
    }
  }

  private def assertSameMaps(hamt: LongHamt, shadow: LongMap[Val]): Unit = {
    assertEquals(hamt.size, shadow.size)
    for (k <- shadow.keySet) {
      val expVal = shadow(k)
      assert(hamt.getOrElse(k, null) eq expVal)
    }
    val arr = hamt.toArray
    assertEquals(arr.length, hamt.size)
    for (v <- arr) {
      assert(shadow(v.value) eq v)
    }
  }

  property("Iteration order should be independent of insertion order (default generator)") {
    forAll { (seed: Long, nums: Set[Long]) =>
      testInsertionOrder(seed, nums)
    }
  }

  property("Iteration order should be independent of insertion order (RIG generator)") {
    myForAll { (seed: Long, nums: Set[Long]) =>
      testInsertionOrder(seed, nums)
    }
  }

  private def testInsertionOrder(seed: Long, nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val nums1 = rng.shuffle(nums.toList)
    val nums2 = rng.shuffle(nums1)
    val hamt1 = hamtFromList(nums1)
    val hamt2 = hamtFromList(nums2)
    assertEquals(hamt1.toArray.toList, hamt2.toArray.toList)
    assertEquals(hamt1.size, nums.size)
    assertEquals(hamt2.size, nums.size)
  }

  property("Ordering should be independent of elements") {
    forAll { (seed: Long, nums1: Set[Long], nums2: Set[Long], nums3: Set[Long]) =>
      val rng = new Random(seed)
      val both = nums1
      val left = nums2 -- both
      val right = (nums3 -- both) -- left
      val hamt1 = hamtFromList(rng.shuffle(both.toList) ++ rng.shuffle(left.toList))
      val hamt2 = hamtFromList(rng.shuffle(right.toList) ++ rng.shuffle(both.toList))
      // common elements must have same order:
      val l1 = hamt1.toArray.toList.map(_.value).filter(both.contains)
      val l2 = hamt2.toArray.toList.map(_.value).filter(both.contains)
      assertEquals(l1.toSet, both)
      assertEquals(l2.toSet, both)
      assertEquals(l1, l2)
      // add disjoint elements (1):
      val hamt1b = addAll(hamt1, rng.shuffle(right.toList))
      val l1b = hamt1b.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n))
      val l2b = hamt2.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n))
      assertEquals(l1b, l2b)
      // add disjoint elements (2):
      val hamt2c = addAll(hamt2, rng.shuffle(left.toList))
      val l1c = hamt1b.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n) || left.contains(n))
      val l2c = hamt2c.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n) || left.contains (n))
      assertEquals(l1c, l2c)
    }
  }

  test("Lots of elements") {
    val N = if (isJvm()) 1000000 else 1000
    val lst = List(42L, 99L, 1024L, Long.MinValue, Long.MaxValue)
    var i = 0
    var hamt = LongHamt.empty
    while (i < N) {
      hamt = hamt.upserted(Val(ThreadLocalRandom.current().nextLong()))
      i += 1
    }
    hamt = addAll(hamt, lst)
    for (n <- lst) {
      assertEquals(hamt.getOrElse(n, Val(0L)), Val(n))
    }
  }

  property("Merging HAMTs") {
    forAll { (seed: Long, nums1: Set[Long], _nums2: Set[Long]) =>
      val rng = new Random(seed)
      val nums2 = _nums2 -- nums1
      val hamt1 = hamtFromList(rng.shuffle(nums1.toList))
      val hamt2 = hamtFromList(rng.shuffle(nums2.toList))
      val merged1 = hamt1.insertedAllFrom(hamt2)
      val merged2 = hamt2.insertedAllFrom(hamt1)
      val expected = hamtFromList(rng.shuffle(nums1.toList) ++ rng.shuffle(nums2.toList))
      val expLst = expected.toArray.toList
      val expSize = (nums1 union nums2).size
      assertEquals(expLst.size, expSize)
      assertEquals(merged1.toArray.toList, expLst)
      assertEquals(merged1.size, expSize)
      assertEquals(merged2.toArray.toList, expLst)
      assertEquals(merged2.size, expSize)
    }
  }

  property("HAMT equals/hashCode") {
    forAll { (seed: Long, nums: Set[Long], num: Long) =>
      val rng = new Random(seed)
      val l1 = rng.shuffle((nums - num).toList)
      val hamt1 = hamtFromList(l1)
      val hamt2 = hamtFromList(rng.shuffle(l1))
      val hamt3 = hamtFromList(l1.reverse)
      assert(hamt1 == hamt1)
      assert(hamt1 == hamt2)
      assert(hamt1 == hamt3)
      assert(hamt2 == hamt1)
      assert(hamt2 == hamt2)
      assert(hamt2 == hamt3)
      assert(hamt3 == hamt1)
      assert(hamt3 == hamt2)
      assert(hamt3 == hamt3)
      val h1 = hamt1.##
      val h2 = hamt2.##
      val h3 = hamt3.##
      assertEquals(h1, h2)
      assertEquals(h1, h3)
      val hamt2b = hamt2.inserted(Val(num))
      assert(hamt2b != hamt1)
      assert(hamt2b != hamt2)
      assert(hamt2b != hamt3)
      assert(hamt1 != hamt2b)
      assert(hamt2 != hamt2b)
      assert(hamt3 != hamt2b)
      val h2b = hamt2b.##
      assertNotEquals(h2b, h1) // with high probability
      val hamt2c = hamt2b.updated(new SpecVal(num)) // has identity equals
      assert(hamt2c != hamt2b)
      assert(hamt2b != hamt2c)
      val h2c = hamt2c.##
      assertEquals(h2c, h2b)
    }
  }

  test("HAMT toString") {
    val h0 = LongHamt.empty
    assertEquals(h0.toString, "Hamt()")
    val h1 = h0.inserted(Val(0x000000ffff000000L))
    assertEquals(h1.toString, "Hamt(Val(1099494850560,fortytwo,true))")
    val h2 = h1.inserted(Val(0xffffff0000ffffffL))
    assertEquals(h2.toString, "Hamt(Val(1099494850560,fortytwo,true), Val(-1099494850561,fortytwo,true))")
  }

  property("forAll") {
    // the predicate in `LongHamt` is `>`
    forAll { (seed: Long, nums: Set[Long]) =>
      val rng = new Random(seed)
      val hamt1 = hamtFromList(rng.shuffle(nums.toList.filter(_ > 42L)))
      assert(hamt1.forAll(42L))
      val hamt1b = hamt1.upserted(Val(1024L))
      assert(!hamt1b.forAll(1024L))
      val nums2 = rng.shuffle(nums.toList.filter(_ <= 42L)) match {
        case Nil => List(42L)
        case lst => lst
      }
      val hamt2 = nums2.foldLeft(hamt1) { (h, n) =>
        h.inserted(Val(n))
      }
      assert(!hamt2.forAll(42L))
    }
  }

  property("isBlue (default generator)") {
    forAll { (seed: Long, nums: Set[Long]) =>
      testIsBlue(seed, nums)
    }
  }

  property("isBlue (RIG generator)") {
    myForAll { (seed: Long, nums: Set[Long]) =>
      testIsBlue(seed, nums)
    }
  }

  private def testIsBlue(seed: Long, nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val evenNums = rng.shuffle(nums.toList.filter(n => (n % 2L) == 0L))
    val oddNums = rng.shuffle(nums.toList.filter(n => (n % 2L) != 0L))
    var hamt = LongHamt.empty
    var size = 0
    assert(hamt.definitelyBlue)
    for (n <- evenNums) {
      hamt = hamt.inserted(Val(n, isBlue = true))
      size += 1
      assert(hamt.definitelyBlue)
      assertEquals(hamt.size, size)
    }
    for (k <- oddNums) {
      hamt = hamt.inserted(Val(k, isBlue = false))
      size += 1
      assert(!hamt.definitelyBlue)
      assertEquals(hamt.size, size)
    }
    if (oddNums.nonEmpty) {
      assertNotEquals(hamt.toArray((), flag = false, nullIfBlue = true), null)
    }
    // it's just an approximation, so overwriting with isBlue = true doesn't change `definitelyBlue`:
    for (k <- oddNums) {
      hamt = hamt.updated(Val(k, isBlue = true))
      assert(!hamt.definitelyBlue)
      assertEquals(hamt.size, size)
    }
    // but copying to an array detects it:
    assertEquals(hamt.toArray((), flag = false, nullIfBlue = true), null)
  }

  test("valuesIterator examples") {
    val c0 = 0x0L
    val c1 = 0x8000000000000000L
    val h0 = LongHamt.empty
    val it00 = h0.valuesIterator
    assert(!it00.hasNext)
    assert(!it00.hasNext)
    try it00.next() catch { case _: NoSuchElementException => () }
    val it01 = h0.valuesIterator
    val it02 = h0.valuesIterator
    assert(!it01.hasNext)
    assert(!it02.hasNext)
    assert(!it00.hasNext)
    val h1 = h0.inserted(Val(c0))
    val it10 = h1.valuesIterator
    val it11 = h1.valuesIterator
    assert(it10.hasNext)
    assert(it10.hasNext)
    assertEquals(it10.next(), Val(c0))
    assert(!it10.hasNext)
    assert(!it10.hasNext)
    assert(it11.hasNext)
    assertEquals(it11.next(), Val(c0))
    assert(!it10.hasNext)
    assert(!it11.hasNext)
    val h2 = h1.inserted(Val(c1))
    val it20 = h2.valuesIterator
    val it21 = h2.valuesIterator
    assert(it20.hasNext)
    assert(it21.hasNext)
    assertEquals(it20.next(), Val(c0))
    assert(it20.hasNext)
    assert(it21.hasNext)
    assertEquals(it20.next(), Val(c1))
    assert(!it20.hasNext)
    assert(!it20.hasNext)
    assert(it21.hasNext)
    assertEquals(it21.next(), Val(c0))
    assertEquals(it21.next(), Val(c1))
    assert(!it20.hasNext)
    assert(!it20.hasNext)
    assert(!it21.hasNext)
    assert(!it21.hasNext)
  }

  property("valuesIterator (default generator)") {
    forAll { (seed: Long, nums: Set[Long]) =>
      testValuesIterator(seed, nums)
    }
  }

  property("valuesIterator (RIG generator)") {
    myForAll { (seed: Long, nums: Set[Long]) =>
      testValuesIterator(seed, nums)
    }
  }

  private def testValuesIterator(seed: Long, nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val h = hamtFromList(rng.shuffle(nums.toList))
    val expected = h.toArray.toList
    val actual = h.valuesIterator.toList
    assertEquals(actual, expected)
  }

  property("packSizeAndBlue") {
    forAll { (_size: Int, _isBlue: Boolean) =>
      val (size, isBlue) = _size match {
        case 0 =>
          // empty HAMT is always blue:
          (0, true)
        case java.lang.Integer.MIN_VALUE =>
          // size is never negative:
          (java.lang.Integer.MAX_VALUE, _isBlue)
        case s =>
          (java.lang.Math.abs(s), _isBlue)
      }
      val h = LongHamt.empty
      val sb: Int = h._packSizeAndBlue(size, isBlue)
      assertEquals(h._unpackSize(sb), size)
      assertEquals(h._unpackBlue(sb), isBlue)
    }
  }
}

object HamtSpec {

  case class LongWr(n: Long) extends Hamt.HasHash {
    final override def hash: Long =
      n
  }

  case class Val(value: Long, extra: String = "fortytwo", isBlue: Boolean = true) extends Hamt.HasKey[LongWr] {

    final override val key: LongWr =
      LongWr(value)

    final override def isTomb: Boolean =
      false

    override def equals(that: Any): Boolean = {
      if (that.isInstanceOf[SpecVal]) {
        that.equals(this)
      } else {
        that match {
          case Val(v, ex, ib) =>
            (value == v) && (extra == ex) && (isBlue == ib)
          case _ =>
            false
        }
      }
    }
  }

  /** This is a hack to have non-equal `Val`s with the same `value` */
  final class SpecVal(v: Long, extra: String = "fortytwo") extends Val(v, extra) {
    final override def equals(that: Any): Boolean =
      equ(this, that)
  }

  /** A simple HAMT of `Long` -> `Val` pairs */
  final class LongHamt(
    _sizeAndBlue: Int,
    _bitmap: Long,
    _contents: Array[AnyRef],
  ) extends Hamt[LongWr, Val, Val, Unit, Long, LongHamt](_sizeAndBlue, _bitmap, _contents) {
    protected final override def isBlue(a: Val): Boolean =
      a.isBlue
    protected final override def newNode(size: Int, bitmap: Long, contents: Array[AnyRef]): LongHamt =
      new LongHamt(size, bitmap, contents)
    protected final override def newArray(size: Int): Array[Val] =
      new Array[Val](size)
    protected final override def convertForArray(a: Val, tok: Unit, dontCare: Boolean): Val =
      a
    protected final override def predicateForForAll(a: Val, tok: Long): Boolean =
      a.value > tok
    final def toArray: Array[Val] =
      this.toArray((), flag = false, nullIfBlue = false)
    final def definitelyBlue: Boolean =
      this.isBlueSubtree
    final def getOrElse(k: Long, default: Val): Val = this.getOrElseNull(k) match {
      case null => default
      case v => v
    }
    final def _packSizeAndBlue(size: Int, isBlue: Boolean): Int = {
      this.packSizeAndBlue(size, isBlue)
    }
    final def _unpackSize(sb: Int): Int = {
      this.unpackSize(sb)
    }
    final def _unpackBlue(sb: Int): Boolean = {
      this.unpackBlue(sb)
    }
  }

  object LongHamt {
    val empty: LongHamt =
      new LongHamt(0, 0L, new Array(0))
  }

  private[mcas] def hamtFromList(lst: List[Long]): LongHamt = {
    addAll(LongHamt.empty, lst)
  }

  private[mcas] def addAll(hamt: LongHamt, lst: List[Long]): LongHamt = {
    lst.foldLeft(hamt) { (hamt, n) => hamt.upserted(Val(n)) }
  }
}
