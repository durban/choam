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

import cats.effect.IO
import cats.syntax.all._

import munit.CatsEffectSuite

import emcas.RefIdGenerator

final class RefIdGeneratorSpec extends CatsEffectSuite with BaseSpec {

  private val gamma =
    0x9e3779b97f4a7c15L

  private val M =
    1024 * 1024

  test("Stepping") {
    val rig = new RefIdGenerator
    val t1 = rig.newThreadLocal()
    val id11 = t1.nextId()
    val t2 = rig.newThreadLocal()
    val id21 = t2.nextId()
    val id12 = t1.nextId()
    val id22 = t2.nextId()
    val s1 = Set(id11, id12, id21, id22)
    assertEquals(s1.size, 4)
    assertEquals(id12 - id11, gamma)
    assertEquals(id22 - id21, gamma)
    assertEquals(id21 - id12, gamma)
    val t3 = rig.newThreadLocal()
    val id13 = t1.nextId()
    val id23 = t2.nextId()
    val id31 = t3.nextId()
    val s2 = s1 union Set(id13, id23, id31)
    assertEquals(s2.size, 7)
    assertEquals(id13 - id22, gamma)
    assertEquals(id23 - id13, 4 * gamma)
    assertEquals(id31 - id23, 4 * gamma)
    val t4 = rig.newThreadLocal()
    val id41 = t4.nextId()
    val id42 = t4.nextId()
    val id32 = t3.nextId()
    val s3 = s2 union Set(id41, id42, id32)
    assertEquals(s3.size, 10)
    assertEquals(id42 - id41, gamma)
    assertEquals(id41 - id32, gamma)
  }

  test("Stepping with arrays") {
    val rig = new RefIdGenerator
    val t1 = rig.newThreadLocal()
    val id11 = t1.nextId()
    val arrBase1 = t1.allocateArrayBlock(8) // get it from global
    val id12 = t1.nextId()
    assertEquals(id12 - id11, gamma)
    val arr11 = RefIdGenerator.compute(arrBase1, 0)
    val arr12 = RefIdGenerator.compute(arrBase1, 1)
    assertEquals(arr12 - arr11, gamma)
    val arr13 = RefIdGenerator.compute(arrBase1, 2)
    assertEquals(arr13 - arr12, gamma)
    val arr18 = RefIdGenerator.compute(arrBase1, 7)
    assertEquals(arr18 - arr11, 7 * gamma)
    val t2 = rig.newThreadLocal()
    val id21 = t2.nextId()
    val id22 = t2.nextId()
    assertEquals(id21 - arr18, gamma)
    assertEquals(id22 - id21, gamma)
    val id13 = t1.nextId()
    assertEquals(id13 - id22, gamma)
    val arrBase2 = t1.allocateArrayBlock(2) // get it from thread-local
    val arr21 = RefIdGenerator.compute(arrBase2, 0)
    val arr22 = RefIdGenerator.compute(arrBase2, 1)
    assertEquals(arr21 - id13, gamma)
    assertEquals(arr22 - arr21, gamma)
    val ids = List(id11, id12, arr11, arr12, arr13, arr18, id21, id22, id13, arr21, arr22)
    assertEquals(ids.toSet.size, ids.size)
  }

  test("Racing") {
    def generate(rig: RefIdGenerator, arr: Array[Long]): IO[Unit] = IO.cede *> IO.delay {
      val tc = rig.newThreadLocal()
      val len = arr.length
      var i = 0
      while (i < len) {
        arr(i) = tc.nextId()
        i += 1
      }
    }

    val N = Runtime.getRuntime().availableProcessors()
    for {
      rig <- IO(new RefIdGenerator)
      arrs <- IO { (1 to N).map(_ => new Array[Long](M)).toVector }
      tasks = arrs.map(arr => generate(rig, arr))
      _ <- tasks.parSequence_
      s <- IO {
        val sb = Set.newBuilder[Long]
        for (arr <- arrs) {
          sb ++= arr
        }
        sb.result()
      }
      _ <- IO(assertEquals(s.size, N * M))
    } yield ()
  }

  test("Lots of IDs from one thread") {
    val rig = new RefIdGenerator
    val t = rig.newThreadLocal()
    var acc = 0L
    var i = Integer.MIN_VALUE
    while (i < Integer.MAX_VALUE) {
      acc ^= t.nextId()
      i += 1
    }
    acc ^= t.nextId()
    acc ^= t.nextId()
    acc ^= t.nextId()
    assertNotEquals(acc, 0L)
    assertNotEquals(rig.newThreadLocal().nextId(), acc)
  }

  test("One ID from lots of threads each") {
    val rig = new RefIdGenerator
    val first = rig.newThreadLocal().nextId() // uses 0, leaks 1
    var acc = 0L
    var last = 0L
    var i = 0L
    while (i <= Integer.MAX_VALUE.toLong) {
      // i=0: uses 2, leaks 3
      // i=1: uses 4, leaks 5
      // ...
      //      uses 2*(i+1)
      // last one is i=MAX => uses 2*(MAX+1)
      last = rig.newThreadLocal().nextId()
      acc ^= last
      i += 1L
    }
    assertNotEquals(acc, 0L)
    assertEquals(last - first, 2L * (Integer.MAX_VALUE.toLong + 1L) * gamma)
    assertEquals(rig.nextIdWithoutThreadLocal() - last, 2L * gamma)
  }
}
