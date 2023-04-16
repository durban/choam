/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package skiplist

import java.lang.Long.{ MAX_VALUE => MAX, MIN_VALUE => MIN }
import java.util.concurrent.ThreadLocalRandom
import java.util.SplittableRandom

import scala.collection.mutable.{ TreeMap => MutTreeMap }
import scala.concurrent.duration._

import munit.FunSuite

import SkipListHelper._

final class SkipListSpec extends FunSuite {

  import SkipListSpec._

  override def munitTimeout: Duration =
    10.minutes

  def mkNewCb(): Callback = new Function1[Right[Nothing, Unit], Unit] {
    final override def apply(r: Right[Nothing, Unit]): Unit = ()
  }

  private val cb0 = mkNewCb()
  private val cb1 = mkNewCb()
  private val cb2 = mkNewCb()
  private val cb3 = mkNewCb()
  private val cb4 = mkNewCb()
  private val cb5 = mkNewCb()

  test("insert / pollFirstIfTriggered") {
    val m = new SkipListMap[Long, Callback]
    assertEquals(m.pollFirstIfTriggered(MIN), null)
    assertEquals(m.pollFirstIfTriggered(MAX), null)
    assertEquals(m.toString, "SkipListMap()")

    m.insertTlr(0L, cb0)
    assertEquals(m.toString, "SkipListMap(...)")
    assertEquals(m.pollFirstIfTriggered(MIN), null)
    assertEquals(m.pollFirstIfTriggered(MAX), cb0)
    assertEquals(m.pollFirstIfTriggered(MAX), null)
    assertEquals(m.pollFirstIfTriggered(MIN), null)

    m.insertTlr(10L, cb0)
    m.insertTlr(30L, cb1)
    m.insertTlr(0L, cb2)
    m.insertTlr(20L, cb3)
    assertEquals(m.pollFirstIfTriggered(-1L), null)
    assertEquals(m.pollFirstIfTriggered(0L), cb2)
    assertEquals(m.pollFirstIfTriggered(0L), null)
    assertEquals(m.pollFirstIfTriggered(10L), cb0)
    assertEquals(m.pollFirstIfTriggered(10L), null)
    assertEquals(m.pollFirstIfTriggered(20L), cb3)
    assertEquals(m.pollFirstIfTriggered(20L), null)
    assertEquals(m.pollFirstIfTriggered(30L), cb1)
    assertEquals(m.pollFirstIfTriggered(30L), null)
    assertEquals(m.pollFirstIfTriggered(MAX), null)
  }

  test("insert / remove (cancel)") {
    val m = new SkipListMap[Long, Callback]
    val r0 = m.insertTlr(0L, cb0)
    val r1 = m.insertTlr(1L, cb1)
    val r5 = m.insertTlr(5L, cb5)
    val r4 = m.insertTlr(4L, cb4)
    val r2 = m.insertTlr(2L, cb2)
    val r3 = m.insertTlr(3L, cb3)

    assertEquals(m.peekFirstTriggerTime(), 0L)
    r0.run()
    assertEquals(m.peekFirstTriggerTime(), 1L)
    assertEquals(m.pollFirstIfTriggered(MAX), cb1)
    assertEquals(m.peekFirstTriggerTime(), 2L)
    r1.run() // NOP
    r3.run()
    assertEquals(m.peekFirstTriggerTime(), 2L)
    assertEquals(m.pollFirstIfTriggered(MAX), cb2)
    assertEquals(m.peekFirstTriggerTime(), 4L)
    assertEquals(m.pollFirstIfTriggered(MAX), cb4)
    assertEquals(m.peekFirstTriggerTime(), 5L)
    r2.run()
    r5.run()
    assertEquals(m.pollFirstIfTriggered(MAX), null)
    r4.run() // NOP
  }

  test("random test") { // TODO: use scalacheck
    val r = new SkipListMap[Long, Callback]
    val s = new Shadow
    var n = 5000000
    val seed = ThreadLocalRandom.current().nextLong()
    println(s"SEED = ${seed}L")
    val rnd = new SplittableRandom(seed)
    def nextNonNegativeLong(): Long = rnd.nextLong() match {
      case MIN => MAX
      case n if n < 0 => -n
      case n => n
    }

    val startNow = MIN + rnd.nextLong(0xffffffL)
    var now = startNow
    val cancellersBuilder = Vector.newBuilder[(Runnable, Runnable)]
    while (n > 0) {
      now += rnd.nextLong(0xffffffffL) // simulate time passing
      val delay = nextNonNegativeLong()
      val cb = mkNewCb()
      val rs = s.insert(now + delay, cb)
      val rr = r.insertTlr(now + delay, cb)
      cancellersBuilder += ((rs, rr))
      if (rnd.nextInt(8) == 0) {
        rs.run()
        rr.run()
      }
      n -= 1
    }

    val cancellers = cancellersBuilder.result()
    var removeExtra = cancellers.size >> 2
    while (removeExtra > 0) {
      val idx = rnd.nextInt(cancellers.size)
      val (rs, rr) = cancellers(idx)
      rs.run()
      rr.run()
      removeExtra -= 1
    }

    while (s.size() > 0) {
      val nt = s.nextTrigger()
      assertEquals(s.pollFirstIfTriggered(nt - 1L), null)
      assertEquals(r.pollFirstIfTriggered(nt - 1L), null)
      val cbs = s.pollFirstIfTriggered(nt)
      val cbr = r.pollFirstIfTriggered(nt)
      assert(cbr ne null)
      assert(cbr eq cbs)
    }
  }
}

final object SkipListSpec {

  final class Shadow private (map: MutTreeMap[Long, Callback]) {

    def this() = {
      this(MutTreeMap.empty)
    }

    final def insert(key: Long, callback: Callback): Runnable = {
      map.put(key, callback)
      () => { map.remove(key); () }
    }

    final def pollFirstIfTriggered(now: Long): Callback = {
      if (map.isEmpty) {
        null
      } else {
        val entry = map.head
        val tt = entry._1
        if (now - tt >= 0) {
          assert(map.remove(entry._1).isDefined)
          entry._2
        } else {
          null
        }
      }
    }

    final def nextTrigger(): Long = {
      val h = map.head
      h._1
    }

    final def size(): Int = {
      map.size
    }
  }
}
