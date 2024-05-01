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

import org.scalacheck.Prop.forAll

final class MutHamtSpec extends ScalaCheckSuite with MUnitUtils with PropertyHelpers {

  import HamtSpec.{ Val, hamtFromList, addAll }
  import MutHamtSpec.LongMutHamt

  // TODO: "HAMT logicalIdx"

  test("necessarySize") {
    val h = LongMutHamt.newEmpty()
    assertEquals(h.necessarySize_public(32, 0), 2)
    assertEquals(h.necessarySize_public(63, 31), 2)
    assertEquals(h.necessarySize_public(16, 0), 4)
    assertEquals(h.necessarySize_public(31, 15), 4)
    assertEquals(h.necessarySize_public(0, 1), 64)
    assertEquals(h.necessarySize_public(54, 55), 64)
  }

  test("physicalIdx") {
    val h = LongMutHamt.newEmpty()
    assertEquals(h.physicalIdx_public(0, size = 1), 0)
    assertEquals(h.physicalIdx_public(63, size = 1), 0)
    assertEquals(h.physicalIdx_public(0, size = 2), 0)
    assertEquals(h.physicalIdx_public(31, size = 2), 0)
    assertEquals(h.physicalIdx_public(32, size = 2), 1)
    assertEquals(h.physicalIdx_public(63, size = 2), 1)
    assertEquals(h.physicalIdx_public(0, size = 32), 0)
    assertEquals(h.physicalIdx_public(1, size = 32), 0)
    assertEquals(h.physicalIdx_public(2, size = 32), 1)
    assertEquals(h.physicalIdx_public(3, size = 32), 1)
    assertEquals(h.physicalIdx_public(62, size = 32), 31)
    assertEquals(h.physicalIdx_public(63, size = 32), 31)
    for (logIdx <- 0 to 63) {
      assertEquals(h.physicalIdx_public(logIdx, size = 64), logIdx)
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
}

object MutHamtSpec {

  import HamtSpec.Val

  final class LongMutHamt(
    logIdx: Int,
    contents: Array[AnyRef],
  ) extends MutHamt[Long, Val, Val, Unit, LongMutHamt](logIdx, contents) {

    protected final override def keyOf(a: Val): Long =
      a.value

    protected final override def hashOf(k: Long): Long =
      k

    protected final override def newNode(logIdx: Int, contents: Array[AnyRef]): LongMutHamt =
      new LongMutHamt(logIdx, contents)

    protected final override def newArray(size: Int): Array[Val] =
      new Array[Val](size)

    protected final override def convertForArray(a: Val, tok: Unit, flag: Boolean): Val =
      a

    final def getOrElse(k: Long, default: Val): Val = this.getOrElseNull(k) match {
      case null => default
      case v => v
    }

    final def toArray: Array[Val] =
      this.copyToArray((), flag = false)
  }

  final object LongMutHamt {
    def newEmpty(): LongMutHamt = {
      new LongMutHamt(logIdx = 0, contents = new Array[AnyRef](1))
    }
  }
}
