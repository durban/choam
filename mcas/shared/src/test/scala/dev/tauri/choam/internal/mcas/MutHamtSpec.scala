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

import scala.collection.immutable.LongMap
import scala.util.Random

import cats.syntax.all._

import munit.ScalaCheckSuite

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

final class MutHamtSpec extends ScalaCheckSuite with MUnitUtils with PropertyHelpers {

  /**
   * The only reason for this nonsense is that dotty LTS
   * apparently has no binary literal syntax (e.g., `0b1011`).
   * So we made our own: `b"1011"`.
   */
  implicit private final class HomemadeBinaryLiteralSyntax(val sc: StringContext) {
    final def b(args: Any*): Int = {
      require(sc.parts.length == 1)
      require(args.length == 0)
      java.lang.Integer.parseInt(sc.parts(0), 2)
    }
  }

  import HamtSpec.{ Val, SpecVal, LongWr, hamtFromList, addAll }
  import MutHamtSpec.LongMutHamt

  override protected def scalaCheckTestParameters: org.scalacheck.Test.Parameters = {
    val p = super.scalaCheckTestParameters
    p.withMaxSize(p.maxSize * (if (isJvm()) 32 else 2))
  }

  property("HAMT logicalIdx") {
    val h = LongMutHamt.newEmpty()

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

  test("necessarySize") {
    val h = LongMutHamt.newEmpty()
    assertEquals(h.necessarySize_public(b"100000", b"000000"), 2)
    assertEquals(h.necessarySize_public(b"111111", b"011111"), 2)
    assertEquals(h.necessarySize_public(b"010000", b"000000"), 4)
    assertEquals(h.necessarySize_public(b"011111", b"001111"), 4)
    assertEquals(h.necessarySize_public(b"000111", b"001110"), 8)
    assertEquals(h.necessarySize_public(b"000011", b"000110"), 16)
    assertEquals(h.necessarySize_public(b"000011", b"000001"), 32)
    assertEquals(h.necessarySize_public(b"000000", b"000001"), 64)
    assertEquals(h.necessarySize_public(b"110110", b"110111"), 64)
    assertEquals(h.necessarySize_public(b"100010", b"000001"), 2)
    assertEquals(h.necessarySize_public(b"100100", b"100101"), 64)
    // pretend it's the last level (effective hash width is 4 bits,
    // because the last 2 bits are always 0, so max. size is 16):
    assertEquals(h.necessarySize_public(b"100000", b"000000"), 2)
    assertEquals(h.necessarySize_public(b"100000", b"110000"), 4)
    assertEquals(h.necessarySize_public(b"111000", b"110000"), 8)
    assertEquals(h.necessarySize_public(b"000000", b"000100"), 16)
  }

  test("physicalIdx") {
    val h = LongMutHamt.newEmpty()
    assertEquals(h.physicalIdx_public(0, size = 1), 0)
    assertEquals(h.physicalIdx_public(63, size = 1), 0)
    assertEquals(h.physicalIdx_public(0, size = 2), 0)
    assertEquals(h.physicalIdx_public(31, size = 2), 0)
    assertEquals(h.physicalIdx_public(32, size = 2), 1)
    assertEquals(h.physicalIdx_public(63, size = 2), 1)
    assertEquals(h.physicalIdx_public(0, size = 16), 0)
    assertEquals(h.physicalIdx_public(1, size = 16), 0)
    assertEquals(h.physicalIdx_public(4, size = 16), 1)
    assertEquals(h.physicalIdx_public(16, size = 16), 4)
    assertEquals(h.physicalIdx_public(0, size = 32), 0)
    assertEquals(h.physicalIdx_public(1, size = 32), 0)
    assertEquals(h.physicalIdx_public(2, size = 32), 1)
    assertEquals(h.physicalIdx_public(3, size = 32), 1)
    assertEquals(h.physicalIdx_public(62, size = 32), 31)
    assertEquals(h.physicalIdx_public(63, size = 32), 31)
    for (logIdx <- 0 to 63) {
      assertEquals(h.physicalIdx_public(logIdx, size = 64), logIdx)
    }
    for (logIdx <- 0 to 15) {
      assertEquals(h.physicalIdx_public(logIdx, size = 16), logIdx >>> 2)
    }
  }

  test("HAMT examples (1)") {
    val c0 = 0x0L
    val c1 = 0x8000000000000000L
    val c2 = 0x4000000000000000L
    val c3 = 0x1L
    val c4 = 0x2L
    val h = LongMutHamt.newEmpty()
    assertEquals(h.size, 0)
    assertEquals(h.toArray.toList, List.empty[Val])
    assertEquals(h.getOrElse(c0, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c1, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c2, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c4, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h.update(Val(c0))).isLeft)
    assertEquals(h.getOrElse(c0, Val(42L)), Val(42L))
    h.insert(Val(c0))
    assertEquals(h.size, 1)
    assertEquals(h.toArray.toList, List(c0).map(Val(_)))
    assertEquals(h.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h.getOrElse(c1, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c2, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c4, Val(42L)), Val(42L))
    val nv = Val(c0)
    h.update(nv)
    assertEquals(h.size, 1)
    assert(h.getOrElse(c0, Val(42L)) eq nv)
    assert(Either.catchOnly[IllegalArgumentException](h.update(Val(c1))).isLeft)
    assertEquals(h.getOrElse(c1, Val(42L)), Val(42L))
    h.insert(Val(c1))
    assertEquals(h.size, 2)
    assertEquals(h.toArray.toList, List(c0, c1).map(Val(_)))
    assertEquals(h.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h.getOrElse(c2, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c4, Val(42L)), Val(42L))
    val nnv = Val(c0)
    h.upsert(nnv)
    assertEquals(h.size, 2)
    assertEquals(h.toArray.toList, List(c0, c1).map(Val(_)))
    assert(h.getOrElse(c0, Val(42L)) eq nnv)
    assert(h.getOrElse(c0, Val(42L)) ne nv)
    assert(Either.catchOnly[IllegalArgumentException](h.update(Val(c2))).isLeft)
    assert(Either.catchOnly[IllegalArgumentException](h.update(Val(c2))).isLeft)
    h.upsert(Val(c2))
    assertEquals(h.size, 3)
    assertEquals(h.toArray.toList, List(c0, c2, c1).map(Val(_)))
    assertEquals(h.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h.getOrElse(c2, Val(42L)), Val(c2))
    assertEquals(h.getOrElse(c3, Val(42L)), Val(42L))
    assertEquals(h.getOrElse(c4, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h.insert(Val(c2))).isLeft)
    assertEquals(h.getOrElse(c2, Val(42L)), Val(c2))
    h.insert(Val(c3)) // collides with c0 at 1st level
    assertEquals(h.size, 4)
    assertEquals(h.toArray.toList, List(c0, c3, c2, c1).map(Val(_)))
    assertEquals(h.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h.getOrElse(c2, Val(42L)), Val(c2))
    assertEquals(h.getOrElse(c3, Val(42L)), Val(c3))
    assertEquals(h.getOrElse(c4, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h.update(Val(99L))).isLeft)
    assertEquals(h.size, 4)
    h.insert(Val(c4))
    assertEquals(h.size, 5)
    assertEquals(h.toArray.toList, List(c0, c3, c4, c2, c1).map(Val(_)))
    assertEquals(h.getOrElse(c0, Val(42L)), Val(c0))
    assertEquals(h.getOrElse(c1, Val(42L)), Val(c1))
    assertEquals(h.getOrElse(c2, Val(42L)), Val(c2))
    assertEquals(h.getOrElse(c3, Val(42L)), Val(c3))
    assertEquals(h.getOrElse(c4, Val(42L)), Val(c4))
  }

  test("HAMT examples (2)") {
    val k1 = 1L << 54
    val k2 = 2L << 54
    val k3 = 6L << 54
    val mutable = LongMutHamt.newEmpty()
    mutable.insert(Val(k1))
    mutable.insert(Val(k2))
    val immutable1 = HamtSpec.LongHamt.empty.inserted(Val(k1)).inserted(Val(k2))
    assertEquals(mutable.toArray.toList, immutable1.toArray.toList)
    mutable.insert(Val(k3))
    val immutable2 = immutable1.inserted(Val(k3))
    assertEquals(mutable.toArray.toList, immutable2.toArray.toList)
  }

  test("HAMT examples (3)") {
    val k0 = 0L
    val k1 = 1L
    val k2 = 2L
    val mutable = LongMutHamt.newEmpty()
    mutable.insert(Val(k0))
    mutable.insert(Val(k1))
    mutable.insert(Val(k2))
    val immutable = HamtSpec.LongHamt.empty.inserted(Val(k0)).inserted(Val(k1)).inserted(Val(k2))
    assertEquals(mutable.toArray.toList, immutable.toArray.toList)
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
    val hamt = LongMutHamt.newEmpty()
    var shadow = LongMap.empty[Val]
    for (n <- nums) {
      val v = Val(n)
      assert(Either.catchOnly[IllegalArgumentException](hamt.update(v)).isLeft)
      if (rng.nextBoolean()) {
        hamt.insert(v)
      } else {
        hamt.upsert(v)
      }
      shadow = shadow.updated(n, v)
      assert(hamt.getOrElse(n, null) eq v)
      assertSameMaps(hamt, shadow)
    }
    for (n <- rng.shuffle(nums)) {
      val nv = Val(n)
      assert(Either.catchOnly[IllegalArgumentException](hamt.insert(nv)).isLeft)
      if (rng.nextBoolean()) {
        hamt.update(nv)
      } else {
        hamt.upsert(nv)
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
    val hamt = LongMutHamt.newEmpty()
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
      val oldSize = hamt.size
      val oldValues = hamt.toArray.toList
      hamt.computeIfAbsent(LongWr(n), token1, nullVis)
      assertEquals(count, 1)
      assertEquals(hamt.size, oldSize)
      assertEquals(hamt.toArray.toList, oldValues)
      val immutable = hamtFromList(oldValues.map(_.value))
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
      hamt.computeIfAbsent(LongWr(n), token2, vis)
      assertEquals(count, 2)
      assertEquals(hamt.getOrElse(n, null), v)
      assertEquals(hamt.size, oldSize + 1)
      assertEquals(hamt.toArray.toList, immutable.inserted(v).toArray.toList)
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
      val oldSize = hamt.size
      val oldValues = hamt.toArray.toList
      assert(Either.catchOnly[AssertionError] {
        hamt.computeIfAbsent(LongWr(n), token3, incorrectVisitor)
      }.isLeft)
      assertEquals(hamt.size, oldSize)
      assertEquals(hamt.toArray.toList, oldValues)
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
      hamt.computeIfAbsent(LongWr(n), token3, vis)
      assertEquals(count, 2)
      assertEquals(e, Val(n))
      assertEquals(hamt.size, oldSize)
      assertEquals(hamt.toArray.toList, oldValues)
    }
  }

  private def testComputeOrModify(seed: Long, _nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val nums = rng.shuffle(_nums.toList)
    val hamt = LongMutHamt.newEmpty()
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
      val oldSize = hamt.size
      val oldValues = hamt.toArray.toList
      val immutable = hamtFromList(oldValues.map(_.value))
      hamt.computeOrModify(LongWr(n), token1, nullVis)
      assertEquals(count, 1)
      assertEquals(hamt.size, oldSize)
      assertEquals(hamt.toArray.toList, oldValues)
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
      hamt.computeOrModify(LongWr(n), token2, vis)
      assertEquals(count, 2)
      assertEquals(hamt.getOrElse(n, null), v)
      assertEquals(hamt.size, oldSize + 1)
      assertEquals(hamt.toArray.toList, immutable.inserted(v).toArray.toList)
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
      val oldSize = hamt.size
      val oldValues = hamt.toArray.toList
      hamt.computeOrModify(LongWr(n), token3, readOnlyVisitor)
      assertEquals(hamt.size, oldSize)
      assertEquals(hamt.toArray.toList, oldValues)
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
      hamt.computeOrModify(LongWr(n), token3, vis)
      assertEquals(count, 2)
      assertEquals(e, Val(n, "foo"))
      assertEquals(hamt.getOrElse(n, Val(42L)), Val(n, "foo"))
      assertEquals(hamt.size, oldSize)
    }
  }

  private def assertSameMaps(hamt: LongMutHamt, shadow: LongMap[Val]): Unit = {
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

  private def mutHamtFromList(lst: List[Long]): LongMutHamt = {
    addAllMut(LongMutHamt.newEmpty(), lst)
  }

  private def addAllMut(hamt: LongMutHamt, lst: List[Long]): LongMutHamt = {
    lst.foreach { n =>
      hamt.upsert(Val(n))
    }
    hamt
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
    val hamt1 = mutHamtFromList(nums1)
    val immutable = hamtFromList(nums1)
    assertEquals(hamt1.toArray.toList, immutable.toArray.toList)
    val hamt2 = mutHamtFromList(nums2)
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
      val list1 = rng.shuffle(both.toList) ++ rng.shuffle(left.toList)
      val hamt1 = mutHamtFromList(list1)
      val immutable0 = hamtFromList(list1)
      assertEquals(hamt1.toArray.toList, immutable0.toArray.toList)
      val hamt2 = mutHamtFromList(rng.shuffle(right.toList) ++ rng.shuffle(both.toList))
      // common elements must have same order:
      val l1 = hamt1.toArray.toList.map(_.value).filter(both.contains)
      val l2 = hamt2.toArray.toList.map(_.value).filter(both.contains)
      assertEquals(l1.toSet, both)
      assertEquals(l2.toSet, both)
      assertEquals(l1, l2)
      // add disjoint elements (1):
      val list2 = rng.shuffle(right.toList)
      addAllMut(hamt1, list2)
      val immutable1 = addAll(immutable0, list2)
      assertEquals(hamt1.toArray.toList, immutable1.toArray.toList)
      val l1b = hamt1.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n))
      val l2b = hamt2.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n))
      assertEquals(l1b, l2b)
      // add disjoint elements (2):
      addAllMut(hamt2, rng.shuffle(left.toList))
      val l1c = hamt1.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n) || left.contains(n))
      val l2c = hamt2.toArray.toList.map(_.value).filter(n => both.contains(n) || right.contains(n) || left.contains (n))
      assertEquals(l1c, l2c)
    }
  }

  test("Lots of elements") {
    val N = if (isJvm()) 1000000 else 1000
    val lst = List(42L, 99L, 1024L, Long.MinValue, Long.MaxValue)
    var i = 0
    val hamt = LongMutHamt.newEmpty()
    while (i < N) {
      hamt.upsert(Val(ThreadLocalRandom.current().nextLong()))
      i += 1
    }
    addAllMut(hamt, lst)
    for (n <- lst) {
      assertEquals(hamt.getOrElse(n, Val(0L)), Val(n))
    }
  }

  property("Merging HAMTs") {
    forAll { (seed: Long, nums1: Set[Long], _nums2: Set[Long]) =>
      val rng = new Random(seed)
      val nums2 = _nums2 -- nums1
      val hamt1 = mutHamtFromList(rng.shuffle(nums1.toList))
      val hamt2 = mutHamtFromList(rng.shuffle(nums2.toList))
      val expected = hamtFromList(rng.shuffle(nums1.toList) ++ rng.shuffle(nums2.toList))
      val expLst = expected.toArray.toList
      val expSize = (nums1 union nums2).size
      assertEquals(expLst.size, expSize)
      hamt1.insertAllFrom(hamt2)
      assertEquals(hamt1.toArray.toList, expLst)
      assertEquals(hamt1.size, expSize)
      // hamt2 should remain unmodified:
      assertEquals(hamt2.toArray.toList, hamtFromList(nums2.toList).toArray.toList)
      assertEquals(hamt2.size, nums2.size)
    }
  }

  property("HAMT equals/hashCode") {
    forAll { (seed: Long, nums: Set[Long], num: Long) =>
      val rng = new Random(seed)
      val l1 = rng.shuffle((nums - num).toList)
      val hamt1 = mutHamtFromList(l1)
      val hamt2 = mutHamtFromList(rng.shuffle(l1))
      val hamt3 = mutHamtFromList(l1.reverse)
      val immutable = hamtFromList(l1)
      assert(hamt1 == hamt1)
      assert(hamt1 == hamt2)
      assert(hamt1 == hamt3)
      assert(hamt1 != immutable)
      assert(hamt2 == hamt1)
      assert(hamt2 == hamt2)
      assert(hamt2 == hamt3)
      assert(hamt2 != immutable)
      assert(hamt3 == hamt1)
      assert(hamt3 == hamt2)
      assert(hamt3 == hamt3)
      assert(hamt3 != immutable)
      assert(immutable != hamt1)
      assert(immutable != hamt2)
      assert(immutable != hamt3)
      val h1 = hamt1.##
      val h2 = hamt2.##
      val h3 = hamt3.##
      val hImmutable = immutable.##
      assertEquals(h1, h2)
      assertEquals(h1, h3)
      assertNotEquals(hImmutable, h1)
      hamt2.insert(Val(num))
      assert(hamt2 != hamt1)
      assert(hamt2 == hamt2)
      assert(hamt2 != hamt3)
      assert(hamt1 != hamt2)
      assert(hamt3 != hamt2)
      val h2b = hamt2.##
      assertNotEquals(h2b, h1) // with high probability
      hamt2.update(new SpecVal(num)) // has identity equals
      val h2c = hamt2.##
      assertEquals(h2c, h2b)
    }
  }

  test("HAMT toString") {
    val h = LongMutHamt.newEmpty()
    assertEquals(h.toString, "MutHamt()")
    h.insert(Val(0x000000ffff000000L))
    assertEquals(h.toString, "MutHamt(Val(1099494850560,fortytwo,true))")
    h.insert(Val(0xffffff0000ffffffL))
    assertEquals(h.toString, "MutHamt(Val(1099494850560,fortytwo,true), Val(-1099494850561,fortytwo,true))")
  }

  property("forAll") {
    // the predicate in `LongMutHamt` is `>`
    forAll { (seed: Long, nums: Set[Long]) =>
      val rng = new Random(seed)
      val hamt = mutHamtFromList(rng.shuffle(nums.toList.filter(_ > 42L)))
      assert(hamt.forAll(42L))
      hamt.upsert(Val(1024L))
      assert(!hamt.forAll(1024L))
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
    val hamt = LongMutHamt.newEmpty()
    var size = 0
    assert(hamt.definitelyBlue)
    assert(hamt.copyToImmutable().definitelyBlue)
    for (n <- evenNums) {
      hamt.insert(Val(n, isBlue = true))
      size += 1
      assert(hamt.definitelyBlue)
      assert(hamt.copyToImmutable().definitelyBlue)
      assertEquals(hamt.size, size)
    }
    for (k <- oddNums) {
      hamt.insert(Val(k, isBlue = false))
      size += 1
      assert(!hamt.definitelyBlue)
      assert(!hamt.copyToImmutable().definitelyBlue)
      assertEquals(hamt.size, size)
    }
    if (oddNums.nonEmpty) {
      assertNotEquals(hamt.copyToArray((), flag = false, nullIfBlue = true), null)
    }
    // it's just an approximation, so overwriting with isBlue = true doesn't change `definitelyBlue`:
    for (k <- oddNums) {
      hamt.update(Val(k, isBlue = true))
      assert(!hamt.definitelyBlue)
      assertEquals(hamt.size, size)
    }
    // however, when copying, we re-check, so this is exact:
    assert(hamt.copyToImmutable().definitelyBlue)
    // copying to an array also detects it:
    assertEquals(hamt.copyToArray((), flag = false, nullIfBlue = true), null)
  }

  property("copyToImmutable (default generator)") {
    forAll { (seed: Long, nums: Set[Long]) =>
      testCopyToImmutable(seed, nums)
    }
  }

  property("copyToImmutable (RIG generator)") {
    myForAll { (seed: Long, nums: Set[Long]) =>
      testCopyToImmutable(seed, nums)
    }
  }

  private def testCopyToImmutable(seed: Long, nums: Set[Long]): Unit = {
    val rng = new Random(seed)
    val nums1 = rng.shuffle(nums.toList)
    val mutable = mutHamtFromList(nums1)
    val immutableExp = hamtFromList(rng.shuffle(nums1))
    val immutableAct = mutable.copyToImmutable()
    assertEquals(immutableAct, immutableExp)
    assertEquals(immutableAct.size, immutableExp.size)
    assertEquals(immutableAct.toArray.toList, immutableExp.toArray.toList)
  }

  test("valuesIterator examples") {
    val c0 = 0x0L
    val c1 = 0x8000000000000000L
    val h = LongMutHamt.newEmpty()
    val it00 = h.valuesIterator
    assert(!it00.hasNext)
    assert(!it00.hasNext)
    try it00.next() catch { case _: NoSuchElementException => () }
    val it01 = h.valuesIterator
    val it02 = h.valuesIterator
    assert(!it01.hasNext)
    assert(!it02.hasNext)
    assert(!it00.hasNext)
    h.insert(Val(c0))
    val it10 = h.valuesIterator
    val it11 = h.valuesIterator
    assert(it10.hasNext)
    assert(it10.hasNext)
    assertEquals(it10.next(), Val(c0))
    assert(!it10.hasNext)
    assert(!it10.hasNext)
    assert(it11.hasNext)
    assertEquals(it11.next(), Val(c0))
    assert(!it10.hasNext)
    assert(!it11.hasNext)
    h.insert(Val(c1))
    val it20 = h.valuesIterator
    val it21 = h.valuesIterator
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
    val h = mutHamtFromList(rng.shuffle(nums.toList))
    val expected = h.toArray.toList
    val actual = h.valuesIterator.toList
    assertEquals(actual, expected)
  }

  property("addToSize") {
    forAll { (_diff: Int, seed: Long, nums: Set[Long]) =>
      val rng = new Random(seed)
      val h = mutHamtFromList(rng.shuffle(nums.toList))
      val oldSize = h.size
      h.setIsBlueTree_public(rng.nextBoolean())
      val oldIsBlue = h.definitelyBlue
      val diff = _diff match {
        case java.lang.Integer.MIN_VALUE =>
          0
        case d =>
          java.lang.Math.abs(d) match {
            case d if d <= (java.lang.Integer.MAX_VALUE - oldSize) =>
              d
            case d =>
              // overflow:
              Either.catchOnly[ArithmeticException] { h.addToSize_public(d) }.fold(
                _ => 0,
                _ => fail("expected an ArithmeticException")
              )
          }
      }
      h.addToSize_public(diff)
      assertEquals(h.size, java.lang.Math.addExact(oldSize, diff))
      assertEquals(oldIsBlue, h.definitelyBlue)
    }
  }

  test("addToSize overflow".ignore) { // TODO: expected falilure
    val h = mutHamtFromList(Nil)
    assertEquals(h.size, 0)
    // val isBlue = ThreadLocalRandom.current().nextBoolean()
    // h.setIsBlueTree_public(isBlue)
    assertEquals(h.size, 0)
    assertEquals(h.definitelyBlue, true)
    h.addToSize_public(Integer.MAX_VALUE - 1)
    assertEquals(h.size, Integer.MAX_VALUE - 1)
    assertEquals(h.definitelyBlue, true)
    val isBlue2 = ThreadLocalRandom.current().nextBoolean()
    h.setIsBlueTree_public(isBlue2)
    try {
      h.addToSize_public(2)
      fail("expected an ArithmeticException")
    } catch {
      case _: ArithmeticException => // ok
    }
    assertEquals(h.size, Integer.MAX_VALUE - 1)
    assertEquals(h.definitelyBlue, isBlue2)
    h.addToSize_public(1)
    assertEquals(h.size, Integer.MAX_VALUE)
    assertEquals(h.definitelyBlue, isBlue2)
    try {
      h.addToSize_public(1)
      fail("expected an ArithmeticException")
    } catch {
      case _: ArithmeticException => // ok
    }
    assertEquals(h.size, Integer.MAX_VALUE)
    assertEquals(h.definitelyBlue, isBlue2)
    h.addToSize_public(0)
    assertEquals(h.size, Integer.MAX_VALUE)
    assertEquals(h.definitelyBlue, isBlue2)
  }

  property("repro") {
    forAll { (seed: Long, flip: Boolean) =>
      val rng = new Random(seed)
      val x1d3 = 0x1d32989eb776f07fL
      val x981 = 0x981f5faca10443fdL
      val x455 = 0x45556be62505908dL
      val x785 = 0x7859b1954a0356ccL // exchanger hole
      val xe5d = 0xe5d970efe9590ff3L
      val _ids = List[Long](x1d3, x981, x455, x785, xe5d)
      val ids = rng.shuffle(_ids)
      val mutHamt1 = LongMutHamt.newEmpty()
      mutHamt1.insert(Val(ids(0)))
      val mutHamt2 = LongMutHamt.newEmpty()
      mutHamt2.insert(Val(ids(1)))
      val hamt1 = mutHamt1.copyToImmutable()
      val hamt2 = mutHamt2.copyToImmutable()
      val merged0 = if (flip) { hamt2 insertedAllFrom hamt1 } else { hamt1 insertedAllFrom hamt2 }
      val merged1 = merged0.inserted(Val(ids(4)))
      val merged2 = merged1.inserted(Val(ids(3)))
      val merged3 = merged2.inserted(Val(ids(2)))
      assertEquals(merged3.size, 5)
      assertEquals(merged3.toArray.toList, List(Val(x1d3), Val(x455), Val(x785), Val(x981), Val(xe5d)))
    }
  }
}

object MutHamtSpec {

  import HamtSpec.{ Val, LongWr, LongHamt }

  final class LongMutHamt(
    logIdx: Int,
    contents: Array[AnyRef],
  ) extends MutHamt[LongWr, Val, Val, Unit, Long, LongHamt, LongMutHamt](logIdx, contents) {

    protected final override def isBlue(a: Val): Boolean =
      a.isBlue

    protected final override def newNode(logIdx: Int, contents: Array[AnyRef]): LongMutHamt =
      new LongMutHamt(logIdx, contents)

    protected final override def newImmutableNode(sizeAndBlue: Int, bitmap: Long, contents: Array[AnyRef]): LongHamt =
      new LongHamt(sizeAndBlue, bitmap, contents)

    protected final override def newArray(size: Int): Array[Val] =
      new Array[Val](size)

    protected final override def convertForArray(a: Val, tok: Unit, flag: Boolean): Val =
      a

    protected final override def predicateForForAll(a: Val, tok: Long): Boolean =
      a.value > tok

    final def getOrElse(k: Long, default: Val): Val = this.getOrElseNull(k) match {
      case null => default
      case v => v
    }

    final def toArray: Array[Val] =
      this.copyToArray((), flag = false, nullIfBlue = false)

    final def definitelyBlue: Boolean =
      this.isBlueTree
  }

  final object LongMutHamt {
    def newEmpty(): LongMutHamt = {
      new LongMutHamt(logIdx = 0, contents = new Array[AnyRef](1))
    }
  }
}
