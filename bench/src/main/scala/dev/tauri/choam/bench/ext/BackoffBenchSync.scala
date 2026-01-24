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
package bench
package ext

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import BackoffBenchSync._

@Fork(1)
@BenchmarkMode(Array(Mode.AverageTime))
class BackoffBenchSync extends BackoffBenchBase {

  /** How many `onSpinWait` calls to do when spinning */
  private[this] final val spinTokens =
    7

  /**
   * 1A. Baseline without IO (main)
   *
   * Main measurement: "do something simple with shared memory."
   */
  @Benchmark
  @Group("baseline")
  def baselineMain(s: St): Int = {
    s.ctr.getAndIncrement()
  }

  /**
   * 1A. Baseline without IO (bg)
   *
   * Background: "do something more complicated" (just so that
   * the "simple thing" is not the only operation). Also touch
   * the same memory that is used by the main measurement.
   */
  @Benchmark
  @Group("baseline")
  def baselineBackground(s: St, t: ThStBase): Unit = {
    background(s, t)
  }

  /**
   * 2. Spinning (main)
   *
   * Main measurement: "do something simple with shared memory,
   * then spin a little."
   */
  @Benchmark
  @Group("onSpinWait")
  def onSpinWaitMain(s: St, t: ThSt): Int = {
    val r = s.ctr.getAndIncrement()
    onSpinWait(spinTokens * t.repeat)
    r
  }

  /**
   * 2. Spinning (bg)
   *
   * Background: "do something more complicated" (just so that
   * the "simple thing" is not the only operation). Also touch
   * the same memory that is used by the main measurement.
   * (No actual spinning here.)
   */
  @Benchmark
  @Group("onSpinWait")
  def onSpinWaitBackground(s: St, t: ThStBase): Unit = {
    background(s, t)
  }

  // Helper methods:

  @tailrec
  private[this] final def onSpinWait(n: Int): Unit = {
    if (n > 0) {
      Thread.onSpinWait()
      onSpinWait(n - 1)
    }
  }

  private[this] final def background(s: St, t: ThStBase): Unit = {
    if (t.contendedBg) {
      simpleThing(s.ctr, s.rnd)
    } else {
      simpleThing(t.threadLocalCtr, t.threadLocalRnd)
    }
  }
}

object BackoffBenchSync {

  @State(Scope.Benchmark)
  class St {

    final val ctr =
      new AtomicInteger

    final val rnd =
      new AtomicLong(ThreadLocalRandom.current().nextLong())
  }

  @State(Scope.Thread)
  class ThStBase {

    /**
     * Whether the operations running
     * in the background should use the
     * same shared memory locations as
     * the main operations.
     */
    @Param(Array("true", "false"))
    var contendedBg =
      false

    final val threadLocalCtr =
      new AtomicInteger

    final val threadLocalRnd =
      new AtomicLong(ThreadLocalRandom.current().nextLong())
  }

  @State(Scope.Thread)
  class ThSt extends ThStBase {

    /**
     * We repeat the backoff actions a number
     * of times, to make sure our measurement
     * makes sense. (E.g., the measured time
     * for `2` should be approximately twice
     * as long as for `1`.)
     */
    @Param(Array("1", "2", "4"))
    var repeat: Int =
      0
  }
}
