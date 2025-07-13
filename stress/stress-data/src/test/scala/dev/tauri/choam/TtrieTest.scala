/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ThreadLocalRandom

import cats.Hash

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLL_Result

import internal.mcas.Mcas
import data.{ Map, MapHelper }

@JCStressTest
@State
@Description("Ttrie insert should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("Some(y), None, Some(x), true"), expect = ACCEPTABLE_INTERESTING, desc = "ins2 wins"),
  new Outcome(id = Array("None, Some(x), Some(y), true"), expect = ACCEPTABLE_INTERESTING, desc = "ins1 wins")
))
class TtrieTest extends StressTestBase {

  private[this] val ttrie =
    TtrieTest.newTtrie714()

  @Actor
  def ins1(r: LLLL_Result): Unit = {
    r.r1 = ttrie.put(14, "x").unsafePerform(this.impl)
  }

  @Actor
  def ins2(r: LLLL_Result): Unit = {
    r.r2 = ttrie.put(0, "y").unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r3 = ttrie.get(0).unsafePerform(this.impl)
    r.r4 = true
    checkVal(1, "1", r)
    checkVal(2, "2", r)
    checkVal(3, "3", r)
    checkVal(4, "4", r)
    checkVal(7, "7", r)
    checkVal(8, "8", r)
    checkVal(9, "9", r)
  }

  private[this] final def checkVal(key: Int, expVal: String, r: LLLL_Result): Unit = {
    val res: String = ttrie.get(key).unsafePerform(this.impl).get
    if (!expVal.equals(res)) {
      r.r4 = res // error
    }
  }
}

object TtrieTest {

  private[this] final def initMcas: Mcas =
    StressTestBase.emcasInst

  private final def newTtrie714(): Map[Int, String] = {
    val h = new Hash[Int] {
      override def eqv(x: Int, y: Int): Boolean =
        x % 14 == y % 14
      override def hash(x: Int): Int =
        x % 7
    }
    val m = MapHelper.ttrie[Int, String](using h).unsafePerform(initMcas)
    m.put(1, "1").unsafePerform(initMcas)
    m.put(2, "2").unsafePerform(initMcas)
    m.put(3, "3").unsafePerform(initMcas)
    m.put(4, "4").unsafePerform(initMcas)
    m.put(7, "7").unsafePerform(initMcas)
    m.put(8, "8").unsafePerform(initMcas)
    m.put(9, "9").unsafePerform(initMcas)
    val tlr = ThreadLocalRandom.current()
    for (_ <- 1 to 25) {
      val key = Iterator.continually(tlr.nextInt(1024)).dropWhile(h.eqv(_, 0)).next()
      m.put(key, (key % 14).toString).unsafePerform(initMcas)
    }
    m
  }

  final def newRandomTtrie(size: Int, avoid: Int): Map[Int, String] = {
    require(size >= 0)
    require(size < 0x10000000)
    val m = MapHelper.ttrie[Int, String].unsafePerform(initMcas)
    val tlr = ThreadLocalRandom.current()
    // save memory by using a single value:
    val value = "e52262dfbfdf08fb"
    var currSize = 0
    while (currSize < size) {
      val key = tlr.nextInt(0x40000000)
      if (key != avoid) {
        if (m.put(key, value).unsafePerform(initMcas).isEmpty) {
          currSize += 1
        } // else: we just overwrote an existing value
      }
    }
    m
  }
}
