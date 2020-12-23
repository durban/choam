/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import java.util.concurrent.{ CountDownLatch, CyclicBarrier }

final class IBRStackDebugSpec extends IBRStackSpec[IBRStackDebug] {

  override protected def name: String =
    "IBRStackDebug"

  override protected def mkEmpty[A]() =
    IBRStackDebug[A]()

  override protected def threadLocalContext[A](): IBRStackFast.TC[A] =
    IBRStackDebug.threadLocalContext()

  override protected def reuseCount[A](stack: IBRStackDebug[A]): Option[Long] =
    Some(stack.debugGc.reuseCount.get())
}

final class IBRStackFastSpec extends IBRStackSpec[IBRStackFast] {

  override protected def name: String =
    "IBRStackFast"

  override protected def mkEmpty[A]() =
    IBRStackFast[A]()

  override protected def threadLocalContext[A](): IBRStackFast.TC[A] =
    IBRStackFast.threadLocalContext()

  override protected def reuseCount[A](stack: IBRStackFast[A]): Option[Long] =
    None
}

abstract class IBRStackSpec[S[a] <: IBRStackFast[a]]
  extends BaseSpecA {

  protected def name: String

  protected def mkEmpty[A](): S[A]

  protected def threadLocalContext[A](): IBRStackFast.TC[A]

  protected def reuseCount[A](stack: S[A]): Option[Long]

  test(s"$name should work") {
    val s = this.mkEmpty[String]()
    val tc = this.threadLocalContext[String]()
    s.push("a", tc)
    s.push("b", tc)
    s.push("c", tc)
    assertEquals(s.tryPop(tc), "c")
    assertEquals(s.tryPop(tc), "b")
    assertEquals(s.tryPop(tc), "a")
    assert(Option(s.tryPop(tc)).isEmpty)
  }

  test(s"$name should reuse nodes from the freelist") {
    val N = 42L
    val SYNC = 128L
    val s = this.mkEmpty[String]()
    val tc = this.threadLocalContext[String]()
    for (i <- 1 to (16 * IBR.emptyFreq)) {
      s.push(i.toString, tc)
    }
    val latch = new CountDownLatch(3)
    val barrier = new CyclicBarrier(2)
    val pusher = new Thread(() => {
      val tc = this.threadLocalContext[String]()
      latch.countDown()
      latch.await()
      for (i <- 1 to (16 * IBR.emptyFreq)) {
        s.push(i.toString, tc)
        if ((i % SYNC) == 0) {
          barrier.await()
        }
      }
    })
    pusher.start()
    val popper = new Thread(() => {
      val tc = this.threadLocalContext[String]()
      latch.countDown()
      latch.await()
      for (i <- 1 to (16 * IBR.emptyFreq)) {
        assert(Option(s.tryPop(tc)).nonEmpty)
        if ((i % SYNC) == 0) {
          barrier.await()
        }
      }
      for (_ <- 1L to N) {
        s.push("42", tc)
      }
    })
    popper.start()
    latch.countDown()
    latch.await()
    pusher.join()
    popper.join()
    this.reuseCount(s).foreach { reuseCount =>
      assert(reuseCount >= (N/2)) // the exact count is non-deterministic
    }
  }

  test(s"$name should copy itself to a List") {
    val s = this.mkEmpty[Int]()
    val tc = this.threadLocalContext[Int]()
    s.push(1, tc)
    s.push(2, tc)
    s.push(3, tc)
    assertEquals(s.unsafeToList(tc), List(3, 2, 1))
  }

  test(s"$name should not leak memory") {
    val s = this.mkEmpty[String]()
    val tc = this.threadLocalContext[String]()
    s.push("1", tc)
    s.push("2", tc)
    s.push("3", tc)
    val N = 1000000
    for (i <- 1 to N) {
      s.push((i + 3).toString(), tc)
      assert(s.tryPop(tc) ne null)
    }
    tc.fullGc()
    assertEquals(tc.getRetiredCount(), 0L) // all of it should've been freed
  }

  test(s"$name#tryPopN should pop `n` items if possible") {
    val s = this.mkEmpty[String]()
    val tc = this.threadLocalContext[String]()
    s.push("1", tc)
    s.push("2", tc)
    s.push("3", tc)
    s.push("4", tc)
    val arr = Array.ofDim[String](3)
    assertEquals(s.tryPopN(arr, 3, tc), 3)
    assertEquals(arr(0), "4")
    assertEquals(arr(1), "3")
    assertEquals(arr(2), "2")
    assertEquals(s.tryPopN(arr, 2, tc), 1)
    assertEquals(arr(0), "1")
    assertEquals(arr(1), "3")
    assertEquals(arr(2), "2")
  }

  test(s"$name#pushAll should push all items") {
    val s = this.mkEmpty[String]()
    val tc = this.threadLocalContext[String]()
    val arr = Array("a", "b", "c", "d", "e")
    s.pushAll(arr, tc)
    assertEquals(s.tryPopN(arr, arr.length, tc), arr.length)
    assertEquals(arr.toList, List("e", "d", "c", "b", "a"))
  }
}
