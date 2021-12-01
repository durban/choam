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

import kcas.EMCAS

/**
 * JMH profiler "plugin" for `Rxn` statistics/measurements.
 *
 * How to use: `-prof dev.tauri.choam.bench.util.RxnProfiler`
 *
 * Configuration: currently none.
 *
 * Measurements:
 * - rxn.retries: number of retries (not including alternatives)
 */
final class RxnProfiler extends InternalProfiler {

  private[this] var retriesBefore: Long =
    0L

  final override def getDescription(): String =
    "RxnProfiler"

  final override def beforeIteration(
    bp: BenchmarkParams,
    ip: IterationParams
  ): Unit = {
    this.retriesBefore = EMCAS.global.countRetries()
  }

  final override def afterIteration(
    bp: BenchmarkParams,
    ip: IterationParams,
    ir: IterationResult
  ): Collection[_ <: Result[_]] = {
    val retriesAfter = EMCAS.global.countRetries()
    val retriesResult = retriesAfter - this.retriesBefore
    List.of[ScalarResult](
      new ScalarResult(
        RxnProfiler.Retries,
        retriesResult.toDouble,
        RxnProfiler.CountUnit,
        AggregationPolicy.SUM,
      ),
    )
  }
}

object RxnProfiler {
  final val Retries = "rxn.retries"
  final val CountUnit = "counts"
}
