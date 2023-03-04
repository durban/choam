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

import cats.kernel.Comparison.EqualTo
import cats.kernel.Comparison.GreaterThan
import cats.kernel.Comparison.LessThan

import munit.FunSuite

final class SkipListSpec extends FunSuite {

  import SkipListSpec._

  override def munitTimeout: Duration =
    5.minutes

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
    val m = new TimerSkipList
    assertEquals(m.pollFirstIfTriggered(MIN), null)
    assertEquals(m.pollFirstIfTriggered(MAX), null)
    assertEquals(m.toString, "TimerSkipList()")

    m.insert(0L, 0L, cb0)
    assertEquals(m.toString, "TimerSkipList(...)")
    assertEquals(m.pollFirstIfTriggered(MIN), null)
    assertEquals(m.pollFirstIfTriggered(MAX), cb0)
    assertEquals(m.pollFirstIfTriggered(MAX), null)
    assertEquals(m.pollFirstIfTriggered(MIN), null)

    m.insert(0L, 10L, cb0)
    m.insert(0L, 30L, cb1)
    m.insert(0L, 0L, cb2)
    m.insert(0L, 20L, cb3)
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
    val m = new TimerSkipList
    val r0 = m.insert(0L, 0L, cb0)
    val r1 = m.insert(0L, 1L, cb1)
    val r5 = m.insert(0L, 5L, cb5)
    val r4 = m.insert(0L, 4L, cb4)
    val r2 = m.insert(0L, 2L, cb2)
    val r3 = m.insert(0L, 3L, cb3)

    assertEquals(m.peekFirstQuiescent(), cb0)
    r0.run()
    assertEquals(m.peekFirstQuiescent(), cb1)
    assertEquals(m.pollFirstIfTriggered(MAX), cb1)
    assertEquals(m.peekFirstQuiescent(), cb2)
    r1.run() // NOP
    r3.run()
    assertEquals(m.peekFirstQuiescent(), cb2)
    assertEquals(m.pollFirstIfTriggered(MAX), cb2)
    assertEquals(m.peekFirstQuiescent(), cb4)
    assertEquals(m.pollFirstIfTriggered(MAX), cb4)
    assertEquals(m.peekFirstQuiescent(), cb5)
    r2.run()
    r5.run()
    assertEquals(m.peekFirstQuiescent(), null)
    assertEquals(m.pollFirstIfTriggered(MAX), null)
    r4.run() // NOP
  }

  test("random test") { // TODO: use scalacheck
    val r = new TimerSkipList
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
      val rs = s.insert(now, delay, cb)
      val rr = r.insert(now, delay, cb)
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

  type Callback = Function1[Right[Nothing, Unit], Unit]

  final object Shadow {

    val comparator: Ordering[(Long, Long)] = new Ordering[(Long, Long)] {
      final override def compare(x: (Long, Long), y: (Long, Long)): Int = {
        val (xTriggerTime, xSeqNo) = x
        val (yTriggerTime, ySeqNo) = y
        // first compare trigger times:
        val d = xTriggerTime - yTriggerTime
        if (d < 0) -1
        else if (d > 0) 1
        else {
          // if times are equal, compare seq numbers:
          if (xSeqNo < ySeqNo) -1
          else if (xSeqNo == ySeqNo) 0
          else 1
        }
      }
    }

    val catsComparator: cats.kernel.Order[(Long, Long)] =
      cats.kernel.Order.fromOrdering(comparator)
  }

  final class Shadow private (map: MutTreeMap[(Long, Long), Callback]) {

    private[this] var seqNo: Long =
      MIN + 1L

    def this() = {
      this(MutTreeMap.empty(Shadow.comparator))
    }

    final def insert(now: Long, delay: Long, callback: Callback): Runnable = {
      require(delay >= 0L)
      val triggerTime = computeTriggerTime(now, delay)
      val sn = this.seqNo
      this.seqNo += 1L
      val key = (triggerTime, sn)
      map.put(key, callback)
      () => { map.remove(key); () }
    }

    final def pollFirstIfTriggered(now: Long): Callback = {
      if (map.isEmpty) {
        null
      } else {
        val entry = map.head
        val tt = entry._1._1
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
      h._1._1
    }

    final def size(): Int = {
      map.size
    }

    private def peekFirstNode(): ((Long, Long), Callback) = {
      if (map.isEmpty) null
      else map.head
    }

    private[skiplist] final def printBaseNodesQuiescent(println: String => Unit): Unit = {
      val it = map.iterator
      while (it.hasNext) {
        val ((tt, sn), cb) = it.next()
        val cbStr = cb match {
          case null => "null"
          case cb => cb.##.toHexString
        }
        println(s"${tt}, ${sn}, ${cbStr}")
      }
    }

    final def printComparisons(println: String => Unit): Unit = {
      val it = map.keysIterator
      var prev: (Long, Long) = null
      while (it.hasNext) {
        val curr = it.next()
        if (prev ne null) {
          val cmp = Shadow.catsComparator.comparison(prev, curr) match {
            case EqualTo => "=="
            case GreaterThan => ">"
            case LessThan => "<"
          }
          println(s"$prev ${cmp} $curr")
        }
        prev = curr
      }
    }

    /**
     * Computes the trigger time in an overflow-safe manner.
     * The trigger time is essentially `now + delay`. However,
     * we must constrain all trigger times in the skip list
     * to be within `Long.MaxValue` of each other (otherwise
     * there will be overflow when comparing in `cpr`). Thus,
     * if `delay` is so big, we'll reduce it to the greatest
     * allowable (in `overflowFree`).
     *
     * From the public domain JSR-166 `ScheduledThreadPoolExecutor`.
     */
    private[this] final def computeTriggerTime(now: Long, delay: Long): Long = {
      val safeDelay = if (delay < (MAX >> 1)) delay else overflowFree(now, delay)
      now + safeDelay
    }

    /**
     * See `computeTriggerTime`. The overflow can happen if
     * a callback was already triggered (based on `now`), but
     * was not removed yet; and `delay` is sufficiently big.
     *
     * From the public domain JSR-166 `ScheduledThreadPoolExecutor`.
     */
    private[this] final def overflowFree(now: Long, delay: Long): Long = {
      val head = peekFirstNode()
      if (head ne null) {
        val headDelay = head._1._1 - now
        if ((headDelay < 0) && (delay - headDelay < 0)) {
          // head was already triggered, and `delay` is big enough,
          // so we must clamp `delay`:
          MAX + headDelay
        } else {
          delay
        }
      } else {
        delay // empty
      }
    }
  }
}
