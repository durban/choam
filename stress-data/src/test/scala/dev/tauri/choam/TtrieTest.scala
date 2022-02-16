/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import mcas.MCAS
import data.Map

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

  private[this] val insert =
    ttrie.put

  @Actor
  def ins1(r: LLLL_Result): Unit = {
    r.r1 = insert.unsafePerform(14 -> "x", this.impl)
  }

  @Actor
  def ins2(r: LLLL_Result): Unit = {
    r.r2 = insert.unsafePerform(0 -> "y", this.impl)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r3 = ttrie.get.unsafePerform(0, this.impl)
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
    val res: String = ttrie.get.unsafePerform(key, this.impl).get
    if (!expVal.equals(res)) {
      r.r4 = res // error
    }
  }
}

object TtrieTest {

  private[this] final def initMcas: MCAS =
    MCAS.EMCAS

  private final def newTtrie714(): Map[Int, String] = {
    val h = new Hash[Int] {
      override def eqv(x: Int, y: Int): Boolean =
        x % 14 == y % 14
      override def hash(x: Int): Int =
        x % 7
    }
    val m = Map.ttrie[Int, String](h).unsafeRun(initMcas)
    m.put.unsafePerform(1 -> "1", initMcas)
    m.put.unsafePerform(2 -> "2", initMcas)
    m.put.unsafePerform(3 -> "3", initMcas)
    m.put.unsafePerform(4 -> "4", initMcas)
    m.put.unsafePerform(7 -> "7", initMcas)
    m.put.unsafePerform(8 -> "8", initMcas)
    m.put.unsafePerform(9 -> "9", initMcas)
    val tlr = ThreadLocalRandom.current()
    for (_ <- 1 to 25) {
      val key = Iterator.continually(tlr.nextInt(1024)).dropWhile(h.eqv(_, 0)).next()
      m.put.unsafePerform(key -> (key % 14).toString, initMcas)
    }
    m
  }

  final def newRandomTtrie(size: Int, avoid: Int): Map[Int, String] = {
    val m = Map.ttrie[Int, String].unsafeRun(initMcas)
    val tlr = ThreadLocalRandom.current()
    // save memory by using a single value:
    val value = "e52262dfbfdf08fb"
    while (m.values.unsafeRun(initMcas).size < size) {
      val key = tlr.nextInt(0x40000000)
      if (key != avoid) {
        m.put.unsafePerform(key -> value, initMcas)
      }
    }
    m
  }
}
