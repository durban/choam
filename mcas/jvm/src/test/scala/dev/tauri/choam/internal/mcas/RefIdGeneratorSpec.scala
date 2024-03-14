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

  test("RefIdGenerator stepping") {
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

  test("RefIdGenerator race") {
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

  test("RefIdGenerator lots of IDs from one thread") {
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
}
