/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.{
  ConcurrentSkipListMap,
  ConcurrentSkipListSet,
  ConcurrentLinkedQueue,
  ConcurrentLinkedDeque,
  ConcurrentHashMap,
}

import java.util.concurrent.atomic.{
  AtomicBoolean,
  AtomicInteger,
  AtomicIntegerArray,
  AtomicLong,
  AtomicLongArray,
  AtomicReference,
  AtomicReferenceArray,
}

import scala.util.control.NonFatal

import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyGuarantee
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

trait LinchkUtils {

  /**
   * We need this to see fatal errors thrown in a forked JVM during a test (which
   * tend to happen with the bytecode-rewriting when using `ModelCheckingOptions`).
   */
  def printFatalErrors[A](block: => A): A = {
    try {
      block
    } catch {
      case ex if !NonFatal(ex) =>
        try { ex.printStackTrace() } catch { case _: Throwable => () }
        throw ex
    }
  }

  def defaultModelCheckingOptions(): ModelCheckingOptions =
    this.fastModelCheckingOptions()

  def fastModelCheckingOptions(): ModelCheckingOptions = {
    // this is the "fast" configuration from the Lincheck paper:
    this.makeModelCheckingOptions(
      scenarios = 30,
      threads = 2,
      operationsPerThread = 3,
      invocationsPerScenario = 1000,
    )
  }

  def longModelCheckingOptions(): ModelCheckingOptions = {
    // this is the "long" configuration from the Lincheck paper:
    this.makeModelCheckingOptions(
      scenarios = 100,
      threads = 3,
      operationsPerThread = 4,
      invocationsPerScenario = 10000,
    )
  }

  private[this] def makeModelCheckingOptions(
    scenarios: Int,
    threads: Int,
    operationsPerThread: Int,
    invocationsPerScenario: Int,
  ): ModelCheckingOptions = {
    import scala.language.reflectiveCalls

    // We assume that methods of certain classes
    // are atomic, so lincheck won't transform them
    // (hopefully this speeds up testing):
    val assumedAtomic: ManagedStrategyGuarantee = {
      def assumedAtomicPred(fullClassName: String): Boolean = {
        assumedAtomicClassNames.contains(fullClassName) ||
        fullClassName.startsWith("scala.collection.immutable.")
      }
      new ManagedStrategyGuarantee.MethodBuilder(KotlinFromScala.function1(assumedAtomicPred))
        .allMethods()
        .treatAsAtomic()
    }

    // Maybe we'll want to increase the linchk timeout
    // (which is private, so we need to do unspeakable
    // things here):
    val timeoutMs = 10000L // default: 10000L
    type Opts = {
      def invocationTimeout$lincheck(timeoutMs: Long): org.jetbrains.kotlinx.lincheck.Options[?, ?]
    }
    def increaseTimeout(mco: ModelCheckingOptions): ModelCheckingOptions = {
      mco.asInstanceOf[Opts].invocationTimeout$lincheck(timeoutMs).asInstanceOf[ModelCheckingOptions]
    }

    increaseTimeout(new ModelCheckingOptions())
      .addGuarantee(assumedAtomic)
      .checkObstructionFreedom(true)
      .iterations(scenarios)
      .threads(threads)
      .actorsPerThread(operationsPerThread)
      .invocationsPerIteration(invocationsPerScenario)
      .actorsBefore(2) // so that we don't work with empty data structures
      .actorsAfter(1) // to have a chance of detecting inconsistent state left
  }

  private val assumedAtomicClassNames: Set[String] = {
    Set(
      classOf[ConcurrentSkipListMap[?, ?]],
      classOf[ConcurrentSkipListSet[?]],
      classOf[ConcurrentHashMap[?, ?]],
      classOf[ConcurrentLinkedQueue[?]],
      classOf[ConcurrentLinkedDeque[?]],
      classOf[AtomicBoolean],
      classOf[AtomicInteger],
      classOf[AtomicIntegerArray],
      classOf[AtomicLong],
      classOf[AtomicLongArray],
      classOf[AtomicReference[?]],
      classOf[AtomicReferenceArray[?]],
    ).map(_.getName())
  }
}
