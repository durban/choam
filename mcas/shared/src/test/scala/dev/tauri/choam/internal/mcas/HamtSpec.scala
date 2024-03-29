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
import scala.collection.immutable.LongMap

import cats.syntax.all._
import cats.data.Chain

import munit.ScalaCheckSuite

import org.scalacheck.{ Gen, Arbitrary, Prop }
import org.scalacheck.util.Buildable
import org.scalacheck.Prop.forAll

final class HamtSpec extends ScalaCheckSuite with MUnitUtils {

  import HamtSpec.{ LongHamt, Val, SpecVal }

  override protected def scalaCheckTestParameters: org.scalacheck.Test.Parameters = {
    val p = super.scalaCheckTestParameters
    p.withMaxSize(p.maxSize * (if (isJvm()) 32 else 2))
  }

  private def genLongWithRig(implicit arb: Arbitrary[Int]): Gen[Long] = {
    arb.arbitrary.map { n =>
      RefIdGen.compute(base = java.lang.Long.MIN_VALUE, offset = n)
    }
  }

  private def myForAll(body: (Long, Set[Long]) => Prop): Prop = {
    val genSeed = Gen.choose[Long](Long.MinValue, Long.MaxValue)
    val arbLongWithRig = Arbitrary { genLongWithRig }
    val genNums = Arbitrary.arbContainer[Set, Long](
      arbLongWithRig,
      Buildable.buildableFactory,
      c => c
    ).arbitrary
    forAll(genSeed, genNums)(body)
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

  test("HAMT examples") {
    val h0 = LongHamt.empty
    assertEquals(h0.size, 0)
    assertEquals(h0.toArray.toList, List.empty[Val])
    assertEquals(h0.getOrElse(0L, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(1L, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(2L, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(0x40L, Val(42L)), Val(42L))
    assertEquals(h0.getOrElse(0xfe000000000000L, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h0.updated(Val(0L))).isLeft)
    val h1 = h0.inserted(Val(0L))
    assertEquals(h1.size, 1)
    assertEquals(h1.toArray.toList, List(0L).map(Val(_)))
    assertEquals(h1.getOrElse(0L, Val(42L)), Val(0L))
    assertEquals(h1.getOrElse(1L, Val(42L)), Val(42L))
    assertEquals(h1.getOrElse(2L, Val(42L)), Val(42L))
    assertEquals(h1.getOrElse(0x40L, Val(42L)), Val(42L))
    assertEquals(h1.getOrElse(0xfe000000000000L, Val(42L)), Val(42L))
    val nv = Val(0L)
    val h1b = h1.updated(nv)
    assertEquals(h1b.size, h1.size)
    assert(h1b.getOrElse(0L, Val(42L)) eq nv)
    assert(Either.catchOnly[IllegalArgumentException](h1.updated(Val(1L))).isLeft)
    val h2 = h1.inserted(Val(1L))
    assertEquals(h2.size, 2)
    assertEquals(h2.toArray.toList, List(0L, 1L).map(Val(_)))
    assertEquals(h2.getOrElse(0L, Val(42L)), Val(0L))
    assertEquals(h2.getOrElse(1L, Val(42L)), Val(1L))
    assertEquals(h2.getOrElse(2L, Val(42L)), Val(42L))
    assertEquals(h2.getOrElse(0x40L, Val(42L)), Val(42L))
    assertEquals(h2.getOrElse(0xfe000000000000L, Val(42L)), Val(42L))
    val nnv = Val(0L)
    val h2b = h2.upserted(nnv)
    assertEquals(h2b.size, h2.size)
    assertEquals(h2b.toArray.toList, List(0L, 1L).map(Val(_)))
    assert(h2b.getOrElse(0L, Val(42L)) eq nnv)
    assert(h2b.getOrElse(0L, Val(42L)) ne nv)
    assert(Either.catchOnly[IllegalArgumentException](h2.updated(Val(2L))).isLeft)
    val h3 = h2.upserted(Val(2L))
    assertEquals(h3.size, 3)
    assertEquals(h3.toArray.toList, List(0L, 1L, 2L).map(Val(_)))
    assertEquals(h3.getOrElse(0L, Val(42L)), Val(0L))
    assertEquals(h3.getOrElse(1L, Val(42L)), Val(1L))
    assertEquals(h3.getOrElse(2L, Val(42L)), Val(2L))
    assertEquals(h3.getOrElse(0x40L, Val(42L)), Val(42L))
    assertEquals(h3.getOrElse(0xfe000000000000L, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h3.inserted(Val(2L))).isLeft)
    val h4 = h3.inserted(Val(0x40L)) // collides with 0L at 1st level
    assertEquals(h4.size, 4)
    assertEquals(h4.toArray.toList, List(0L, 0x40L, 1L, 2L).map(Val(_)))
    assertEquals(h4.getOrElse(0L, Val(42L)), Val(0L))
    assertEquals(h4.getOrElse(1L, Val(42L)), Val(1L))
    assertEquals(h4.getOrElse(2L, Val(42L)), Val(2L))
    assertEquals(h4.getOrElse(0x40L, Val(42L)), Val(0x40L))
    assertEquals(h4.getOrElse(0xfe000000000000L, Val(42L)), Val(42L))
    assert(Either.catchOnly[IllegalArgumentException](h4.updated(Val(99L))).isLeft)
    val h5 = h4.inserted(Val(0xfe000000000000L))
    assertEquals(h5.size, 5)
    assertEquals(h5.toArray.toList, List(0L, 0xfe000000000000L, 0x40L, 1L, 2L).map(Val(_)))
    assertEquals(h5.getOrElse(0L, Val(42L)), Val(0L))
    assertEquals(h5.getOrElse(1L, Val(42L)), Val(1L))
    assertEquals(h5.getOrElse(2L, Val(42L)), Val(2L))
    assertEquals(h5.getOrElse(0x40L, Val(42L)), Val(0x40L))
    assertEquals(h5.getOrElse(0xfe000000000000L, Val(42L)), Val(0xfe000000000000L))
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

  private def hamtFromList(lst: List[Long]): LongHamt = {
    addAll(LongHamt.empty, lst)
  }

  private def addAll(hamt: LongHamt, lst: List[Long]): LongHamt = {
    lst.foldLeft(hamt) { (hamt, n) => hamt.upserted(Val(n)) }
  }

  property("Iteration order should be independent of insertion order (default generator)") {
    forAll { (seed: Long, nums: Set[Long]) =>
      testInserionOrder(seed, nums)
    }
  }

  property("Iteration order should be independent of insertion order (RIG generator)") {
    myForAll { (seed: Long, nums: Set[Long]) =>
      testInserionOrder(seed, nums)
    }
  }

  private def testInserionOrder(seed: Long, nums: Set[Long]): Unit = {
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
      assertEquals(merged1.toArray.toList, expLst)
      assertEquals(merged1.size, expLst.size)
      assertEquals(merged2.toArray.toList, expLst)
      assertEquals(merged2.size, expLst.size)
    }
  }

  property("HAMT foldLeft") {
    forAll { (seed: Long, nums: Set[Long]) =>
      val rng = new Random(seed)
      val hamt1 = hamtFromList(rng.shuffle(nums.toList))
      val hamt2 = hamtFromList(rng.shuffle(nums.toList))
      val s1 = hamt1.foldLeft(Chain.empty)
      val s2 = hamt2.foldLeft(Chain.empty)
      assertEquals(s1, s2)
      assertEquals(s1.toList.toSet, nums.map(Val(_)))
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
    assertEquals(h1.toString, "Hamt(Val(1099494850560))")
    val h2 = h1.inserted(Val(0xffffff0000ffffffL))
    assertEquals(h2.toString, "Hamt(Val(1099494850560), Val(-1099494850561))")
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
}

object HamtSpec {

  /** Just a `Long`, but has its own identity */
  case class Val(value: Long) {
    override def equals(that: Any): Boolean = {
      if (that.isInstanceOf[SpecVal]) {
        that.equals(this)
      } else {
        that match {
          case Val(v) =>
            value == v
          case _ =>
            false
        }
      }
    }
  }

  /** This is a hack to have non-equal `Val`s with the same `value` */
  final class SpecVal(v: Long) extends Val(v) {
    final override def equals(that: Any): Boolean =
      equ(this, that)
  }

  /** A simple HAMT of `Long` -> `Val` pairs */
  final class LongHamt(
    _size: Int,
    _bitmap: Long,
    _contents: Array[AnyRef],
  ) extends Hamt[Val, Val, Unit, Long, Chain[Val], LongHamt](_size, _bitmap, _contents) {
    protected final override def hashOf(a: Val): Long =
      a.value
    protected final override def newNode(size: Int, bitmap: Long, contents: Array[AnyRef]): LongHamt =
      new LongHamt(size, bitmap, contents)
    protected final override def newArray(size: Int): Array[Val] =
      new Array[Val](size)
    protected final override def convertForArray(a: Val, tok: Unit): Val =
      a
    protected final override def convertForFoldLeft(s: Chain[Val], v: Val): Chain[Val] =
      s :+ v
    protected final override def predicateForForAll(a: Val, tok: Long): Boolean =
      a.value > tok
    final def toArray: Array[Val] =
      this.toArray(())
    final def getOrElse(k: Long, default: Val): Val = this.getOrElseNull(k) match {
      case null => default
      case v => v
    }
  }

  object LongHamt {
    val empty: LongHamt =
      new LongHamt(0, 0L, new Array(0))
  }
}
