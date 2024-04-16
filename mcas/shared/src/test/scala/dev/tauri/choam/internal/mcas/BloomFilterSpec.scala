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

import munit.ScalaCheckSuite

import org.scalacheck.{ Arbitrary, Gen }
import org.scalacheck.Prop.forAll

object BloomFilterSpec {

  final case class Wrapper(
    left: Long,
    right: Long,
  ) {

    import BloomFilter.{ insertLeft, insertRight }

    final def insert(ref: MemoryLocation[_]): Wrapper = {
      Wrapper(left = insertLeft(left, ref), right = insertRight(right, ref))
    }

    final def definitelyNotContains(ref: MemoryLocation[_]): Boolean =
      BloomFilter.definitelyNotContains(left, right, ref)

    final def maybeContains(ref: MemoryLocation[_]): Boolean =
      BloomFilter.maybeContains(left, right, ref)
  }

  final object Wrapper {
    def empty: Wrapper = Wrapper(0L, 0L)
  }
}

final class BloomFilterSpec extends ScalaCheckSuite {

  import BloomFilterSpec._

  implicit def arbMemLoc[A](implicit arbA: Arbitrary[A]): Arbitrary[MemoryLocation[A]] = Arbitrary {
    arbA.arbitrary.flatMap { a =>
      Gen.delay { MemoryLocation.unsafe[A](a) }
    }
  }

  property("Bloom filter") {
    forAll { (_refs: Set[MemoryLocation[String]]) =>
      val refs = _refs.toList
      var bf = Wrapper.empty
      var n = 0
      var falsePositives = 0
      for (ref <- refs) {
        if (bf.maybeContains(ref)) {
          falsePositives += 1
        }
        bf = bf.insert(ref)
        n += 1
        assert(bf.maybeContains(ref))
      }
      if (n != 0) {
        val falsePosRate = falsePositives.toDouble / n.toDouble
        assert(falsePosRate < 1.0) // TODO: this is always true
      }
    }
  }

  property("BloomFilter64") {
    val N = 8
    val S = 10 * N
    val genSet = Gen.containerOfN[Set, Int](
      S,
      Gen.choose(min = Int.MinValue, max = Int.MaxValue)
    )
    forAll(genSet) { (items: Set[Int]) =>
      assertEquals(items.size, S)
      val itemList = items.toList
      val itemsToInsert = itemList.take(N)
      val itemsToCheck = itemList.drop(N)
      val bf = itemsToInsert.foldLeft(0L) { (bf, item) =>
        BloomFilter64.insert(bf, item)
      }
      assert(itemsToInsert.forall(item => BloomFilter64.maybeContains(bf, item)))
      val falsePositives = itemsToCheck.foldLeft(0) { (falsePositives, item) =>
        val exp = if (BloomFilter64.definitelyAbsent(bf, item)) {
          BloomFilter64.insert(bf, item)
        } else {
          0L
        }
        assertEquals(BloomFilter64.insertIfAbsent(bf, item), exp)
        val estimatedSize = BloomFilter64.estimatedSize(bf)
        val ratio = estimatedSize.toDouble / N.toDouble
        assert((clue(ratio) >= 0.5) && (ratio <= 1.5))
        if (BloomFilter64.maybeContains(bf, item)) falsePositives + 1
        else falsePositives
      }
      assert(clue(falsePositives.toDouble / itemsToCheck.size.toDouble) < 0.25) // should be around 0.025
    }
  }

  test("BloomFilter64#estimatedSize") {
    assertEquals(BloomFilter64.estimatedSize(0L), 0)
    val bf1 = BloomFilter64.insert(0L, 42)
    assertEquals(BloomFilter64.estimatedSize(bf1), 1)
    val bf2 = BloomFilter64.insert(bf1, 0x57abf0cd)
    assertEquals(BloomFilter64.estimatedSize(bf2), 2)
  }

  test("BloomFilter64 false positive rate".ignore) {
    val K = 10000
    val L = 1000
    val N = 10

    @tailrec
    def getUniqueItem(shadow: Set[Int]): Int = {
      val candidate = (new Object).hashCode()
      // val candidate = ThreadLocalRandom.current().nextInt()
      if (shadow.contains(candidate)) {
        getUniqueItem(shadow) // hash collision, retry
      } else {
        candidate
      }
    }

    val (popCnt, fps) = (1 to K).foldLeft((0, 0)) {
      case ((popCnt, fps), _) =>
        val (set, shadow) = (1 to N).foldLeft((0L, Set.empty[Int])) {
          case ((bf, shadow), _) =>
            val item = getUniqueItem(shadow)
            val newBf = BloomFilter64.insert(bf, item)
            val newShadow = shadow + item
            (newBf, newShadow)
        }
        val fps2 = (1 to L).foldLeft(0) {
          case (fps, _) =>
            val item = getUniqueItem(shadow)
            if (BloomFilter64.maybeContains(set, item)) {
              // false positive
              fps + 1
            } else {
              fps
            }

        }
        (popCnt + java.lang.Long.bitCount(set), fps + fps2)
    }
    println(s"K = ${K}, L = ${L}, N = ${N}")
    println(s"Avg. population cnt: ${popCnt.toDouble / K.toDouble}")
    println(s"False positive rate: ${fps.toDouble / (K * L).toDouble}")
  }
}
