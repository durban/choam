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
        assert(falsePosRate < 1.0)
      }
    }
  }
}
