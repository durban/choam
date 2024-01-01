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

import java.util.concurrent.ThreadLocalRandom

import scala.collection.concurrent.TrieMap

import cats.effect.SyncIO

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZZ_Result

import data.{ Stack, StackHelper }
import ce._
import internal.mcas.Mcas

// @JCStressTest
@State
@Description("Treiber stack global pop/push should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("true, true, true"), expect = ACCEPTABLE, desc = "Pop sees consistent values")
))
class TreiberStackGlobalTest extends StressTestBase {

  import TreiberStackGlobalTest._

  private[this] val stacks =
    getStacks(impl)

  private[this] val stack1 =
    stacks._1

  private[this] val stack2 =
    stacks._2

  private[this] val _push =
    stack1.push * stack2.push

  private[this] val tryPop =
    stack1.tryPop * stack2.tryPop

  @Actor
  def push(): Unit = {
    val s = Integer.toString(ThreadLocalRandom.current().nextInt(0, 4096))
    _push.unsafePerform(s, this.impl)
    ()
  }

  @Actor
  def pop(r: ZZZ_Result): Unit = {
     val (v1, v2) = tryPop.unsafeRun(this.impl)
     if (v1.isDefined) r.r1 = true
     if (v2.isDefined) r.r2 = true
     // pop must always see the same values:
     if (v1.get == v2.get) r.r3 = true
  }
}

/**
 * We're fooling jcstress: every run uses the same 2 global stacks
 *
 * However, each k-CAS implementation have their own set of stacks.
 */
object TreiberStackGlobalTest {

  private[this] final val N =
    1024 * 128

  private[this] val stacks =
    new TrieMap[Mcas, (Stack[String], Stack[String])]

  private def getStacks(impl: Mcas): (Stack[String], Stack[String]) = {
    def mkNew() = {
      val stack1 = StackHelper.treiberStackFromList[SyncIO, String](List.fill(N)("z")).unsafeRunSync()
      val stack2 = StackHelper.treiberStackFromList[SyncIO, String](List.fill(N)("z")).unsafeRunSync()
      (stack1, stack2)
    }
    stacks.getOrElseUpdate(impl, mkNew())
  }
}
