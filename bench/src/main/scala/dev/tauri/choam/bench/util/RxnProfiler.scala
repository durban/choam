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

import java.{ util => ju }
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.infra.{ BenchmarkParams, IterationParams }
import org.openjdk.jmh.results.{ IterationResult, Result, ScalarResult, AggregationPolicy }

import mcas.MCAS.EMCAS

/**
 * JMH profiler "plugin" for `Rxn` statistics/measurements.
 *
 * How to use: `-prof dev.tauri.choam.bench.util.RxnProfiler`
 *
 * Configuration: currently none.
 *
 * Measurements:
 * - rxn.retriesPerCommit:
 *   average number of retries (including alternatives) per commit
 * - rxn.exchangesPerSec:
 *   average number of successful exchanges per second (note: this data
 *   is not collected for every exchanger, only for ones created by
 *   `RxnProfiler.profiledExchanger`)
 */
final class RxnProfiler extends InternalProfiler {

  private[this] var commitsBefore: Long =
    0L

  private[this] var retriesBefore: Long =
    0L

  private[this] var exchangesBefore: Long =
    0L

  private[this] var beforeTime: Long =
    0L

  private[this] var afterTime: Long =
    0L

  final override def getDescription(): String =
    "RxnProfiler"

  final override def beforeIteration(
    bp: BenchmarkParams,
    ip: IterationParams
  ): Unit = {
    val cr = EMCAS.countCommitsAndRetries()
    this.commitsBefore = cr._1
    this.retriesBefore = cr._2
    this.exchangesBefore = RxnProfiler.exchangeCounter.sum()
    this.beforeTime = System.nanoTime()
  }

  final override def afterIteration(
    bp: BenchmarkParams,
    ip: IterationParams,
    ir: IterationResult
  ): ju.Collection[_ <: Result[_]] = {
    this.afterTime = System.nanoTime()
    val res = new ju.ArrayList[ScalarResult]
    res.addAll(countRetriesPerCommit())
    res.addAll(countExchanges())
    res
  }

  private[this] def countRetriesPerCommit(): ju.List[ScalarResult] = {
    val cr = EMCAS.countCommitsAndRetries()
    val commitsAfter = cr._1
    val retriesAfter = cr._2
    val allCommits = commitsAfter - this.commitsBefore
    val allRetries = retriesAfter - this.retriesBefore
    val retriesPerCommit =  if (allCommits == 0L) {
      Double.NaN
    } else {
      allRetries.toDouble / allCommits.toDouble
    }
    ju.List.of(
      new ScalarResult(
        RxnProfiler.RetriesPerCommit,
        retriesPerCommit,
        RxnProfiler.UnitRetriesPerCommit,
        AggregationPolicy.AVG,
      )
    )
  }

  private[this] def countExchanges(): ju.List[ScalarResult] = {
    val exchangesAfter = RxnProfiler.exchangeCounter.sum()
    val exchanges = (exchangesAfter - exchangesBefore).toDouble
    val elapsedTime = this.afterTime - this.beforeTime
    val exchangesPerSecond = if (elapsedTime == 0L) {
      Double.NaN
    } else {
      val elapsedSeconds =
        TimeUnit.SECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS)
      exchanges / elapsedSeconds.toDouble
    }
    ju.List.of(
      new ScalarResult(
        RxnProfiler.ExchangesPerSecond,
        exchangesPerSecond,
        RxnProfiler.UnitExchangesPerSecond,
        AggregationPolicy.AVG,
      ),
      new ScalarResult(
        RxnProfiler.Exchanges,
        exchanges,
        RxnProfiler.UnitCount,
        AggregationPolicy.SUM,
      )
    )
  }
}

object RxnProfiler {

  final val RetriesPerCommit = "rxn.retriesPerCommit"
  final val UnitRetriesPerCommit = "retries/commit"
  final val ExchangesPerSecond = "rxn.exchangesPerSec"
  final val UnitExchangesPerSecond = "xchg/s"
  final val Exchanges = "rxn.exchanges"
  final val UnitCount = "counts"

  final def profiledExchanger[A, B]: Axn[Exchanger[A, B]] =
    Exchanger.profiled[A, B](this.exchangeCounter)

  private[choam] final val exchangeCounter: LongAdder =
    new LongAdder
}
