/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package util

import java.util.{ Collection, List }

import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.infra.{ BenchmarkParams, IterationParams }
import org.openjdk.jmh.results.{ IterationResult, Result, ScalarResult, AggregationPolicy }

import mcas.EMCAS

/**
 * JMH profiler "plugin" for `Rxn` statistics/measurements.
 *
 * How to use: `-prof dev.tauri.choam.bench.util.RxnProfiler`
 *
 * Configuration: currently none.
 *
 * Measurements:
 * - rxn.retriesPerCommit:
 *   average number of retries (not including alternatives) per commit
 */
final class RxnProfiler extends InternalProfiler {

  private[this] var commitsBefore: Long =
    0L

  private[this] var retriesBefore: Long =
    0L

  final override def getDescription(): String =
    "RxnProfiler"

  final override def beforeIteration(
    bp: BenchmarkParams,
    ip: IterationParams
  ): Unit = {
    val cr = EMCAS.global.countCommitsAndRetries()
    this.commitsBefore = cr._1
    this.retriesBefore = cr._2
  }

  final override def afterIteration(
    bp: BenchmarkParams,
    ip: IterationParams,
    ir: IterationResult
  ): Collection[_ <: Result[_]] = {
    val cr = EMCAS.global.countCommitsAndRetries()
    val commitsAfter = cr._1
    val retriesAfter = cr._2
    val allCommits = commitsAfter - this.commitsBefore
    val allRetries = retriesAfter - this.retriesBefore
    val retriesPerCommit = allRetries.toDouble / allCommits.toDouble
    List.of[ScalarResult](
      new ScalarResult(
        RxnProfiler.RetriesPerCommit,
        retriesPerCommit,
        RxnProfiler.UnitRetriesPerCommit,
        AggregationPolicy.AVG,
      ),
    )
  }
}

object RxnProfiler {
  final val RetriesPerCommit = "rxn.retriesPerCommit"
  final val UnitRetriesPerCommit = "retries/commit"
  final val UnitCount = "counts"
}
