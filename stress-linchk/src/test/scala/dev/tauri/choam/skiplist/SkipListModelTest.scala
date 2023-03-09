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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.{ ParameterGenerator, IntGen }
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param, StateRepresentation }

import munit.FunSuite
import java.util.concurrent.atomic.AtomicLong

final class SkipListModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking test of TimerSkipList") {
    val opts = defaultModelCheckingOptions()
      .checkObstructionFreedom(false)
      .iterations(1000)
      .invocationsPerIteration(1000)
      .threads(2)
      .actorsBefore(5)
      .actorsPerThread(3)
      .actorsAfter(0)
      .sequentialSpecification(classOf[SkipListModelTest.TestStateSequential])
    printFatalErrors {
      LinChecker.check(classOf[SkipListModelTest.TestState], opts)
    }
  }
}

final object SkipListModelTest {

  type Callback = Function1[Right[Nothing, Unit], Unit]

  final val NOW = 0L
  final val MAX_TT = NOW + 4096L
  final val LATER = MAX_TT + 4096L

  final class GeneratedCallback(val id: Int)
    extends Function1[Right[Nothing, Unit], Unit] with Serializable {

    final override def apply(r: Right[Nothing, Unit]): Unit = ()
    final override def toString: String = s"Callback(#${id.toHexString})"
  }

  final class CbGen(configuration: String) extends ParameterGenerator[Callback] {

    private[this] val intGen = new IntGen(configuration)

    final override def generate(): Callback = {
      new GeneratedCallback(intGen.generate().intValue())
    }
  }

  final class TriggerTimeGen(configuration: String) extends ParameterGenerator[Long] {

    private[this] val intGen = new IntGen(configuration)

    final override def generate(): Long = {
      val n = intGen.generate().intValue().toLong % MAX_TT
      if (n < 0L) -n else n
    }
  }

  final class SeqNoGen(@unused configuration: String) extends ParameterGenerator[Long] {

    // TimerSkipList assumes seqNo's are unique,
    // so we must generate always different ones here

    private[this] val seqNo =
      new AtomicLong(0L)

    final override def generate(): Long = {
      seqNo.getAndIncrement()
    }
  }

  @Param(name = "triggerTime", gen = classOf[TriggerTimeGen])
  @Param(name = "seqNo", gen = classOf[SeqNoGen])
  @Param(name = "callback", gen = classOf[CbGen])
  class TestState {

    private[this] val m: TimerSkipList =
      new TimerSkipList

    @StateRepresentation
    def stateRepr(): String = {
      val lst = List.newBuilder[String]
      m.printRepr(lst += _)
      lst.result().mkString("{", "; ", "}")
    }

    @Operation
    def insert(triggerTime: Long, seqNo: Long, callback: Callback): Unit = {
      m.put(triggerTime, seqNo, callback)
      ()
    }

    @Operation
    def pollFirstIfTriggered(): Callback = {
      m.pollFirstIfTriggered(now = LATER)
    }

    @Operation
    def cancel(triggerTime: Long, seqNo: Long): Boolean = {
      m.remove(triggerTime, seqNo)
    }
  }

  @Param(name = "triggerTime", gen = classOf[TriggerTimeGen])
  @Param(name = "seqNo", gen = classOf[SeqNoGen])
  @Param(name = "callback", gen = classOf[CbGen])
  class TestStateSequential extends VerifierState {

    private[this] val m =
      scala.collection.mutable.TreeMap.empty[(Long, Long), Callback]

    override def extractState(): Map[(Long, Long), Callback] = {
      m.toMap
    }

    @StateRepresentation
    def stateRepr(): String = {
      m.toList.map {
        case ((tt, sn), cb) => s"[${tt}, ${sn}, ${cb.toString()}]"
      }.mkString("{", "; ", "}")
    }

    @Operation
    def insert(triggerTime: Long, seqNo: Long, callback: Callback): Unit = {
      m.put(triggerTime -> seqNo, callback)
      ()
    }

    @Operation
    def pollFirstIfTriggered(): Callback = {
      val now = LATER
      val it = m.iterator
      if (it.hasNext) {
        val (k @ (tt, _), cb) = it.next()
        if (now - tt >= 0) { // triggered
          m.remove(k) match {
            case Some(cb2) =>
              assert(cb2 eq cb)
              cb2
            case None =>
              assert(false)
              null // unreachable
          }
        } else {
          null
        }
      } else {
        null
      }
    }

    @Operation
    def cancel(triggerTime: Long, seqNo: Long): Boolean = {
      m.remove(triggerTime -> seqNo).isDefined
    }
  }
}
