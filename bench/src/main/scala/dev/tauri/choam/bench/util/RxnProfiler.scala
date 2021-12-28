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

import cats.syntax.all._

import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.infra.{ BenchmarkParams, IterationParams }
import org.openjdk.jmh.results.{ IterationResult, Result, ScalarResult, AggregationPolicy }

import com.monovore.decline.{ Opts, Command }

import mcas.MCAS.EMCAS

/**
 * JMH profiler "plugin" for `Rxn` statistics/measurements.
 *
 * How to use: `-prof dev.tauri.choam.bench.util.RxnProfiler`
 *
 * Configuration:
 * `-prof "dev.tauri.choam.bench.util.RxnProfiler:opt1;opt2;opt3"`
 *
 * Available options are:
 * - retries (-> rxn.retriesPerCommit)
 * - exchanges (-> rxn.exchangesPerSec)
 * - exchangeCount (-> rxn.exchangeCount)
 *
 * Measurements:
 * - rxn.retriesPerCommit:
 *   average number of retries (including alternatives) per commit
 * - rxn.exchangesPerSec:
 *   average number of successful exchanges per second (note: this data
 *   is not collected for every exchanger, only for ones created by
 *   `RxnProfiler.profiledExchanger`)
 * - rxn.exchangeCount:
 *   like `rxn.exchangesPerSec`, but reports the count of successful
 *   exchanges
 */
final class RxnProfiler(configLine: String) extends InternalProfiler {

  import RxnProfiler._

  def this() = {
    this("")
  }

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

  private[this] val config: Config = {
    val p = Command("rxn", "RxnProfiler") {
      val debug = Opts.flag("debug", short = "d", help = "debug mode").orFalse
      val retries = Opts.flag("retries", help = "retries / commit").orFalse
      val exchangesPs = Opts.flag("exchanges", help = "exchanges / sec").orFalse
      val exchangeCount = Opts.flag("exchangeCount", help = "exchange count").orFalse
      val cfg = (retries, exchangesPs, exchangeCount).mapN { (r, eps, ec) =>
        Config(retriesPerCommit = r, exchangesPerSecond = eps, exchangeCount = ec)
      }
      (debug, cfg).mapN { (debug, cfg) =>
        if (debug) {
          cfg || Config.debug
        } else {
          cfg
        }
      }
    }
    val args = Option(configLine).getOrElse("").split(";").map { opt =>
      if (opt.nonEmpty) {
        if (opt.size > 1) "--" + opt
        else "-" + opt
      } else {
        opt
      }
    }.toList match {
      case "" :: Nil => Nil
      case lst => lst
    }
    args match {
      case _ :: _ =>
        p.parse(args).fold(
          error => throw new IllegalArgumentException(error.toString),
          ok => ok,
        )
      case Nil =>
        Config.default
    }
  }

  final override def getDescription(): String =
    "RxnProfiler"

  final override def beforeIteration(
    bp: BenchmarkParams,
    ip: IterationParams
  ): Unit = {
    if (config.retriesPerCommit) {
      val cr = EMCAS.countCommitsAndRetries()
      this.commitsBefore = cr._1
      this.retriesBefore = cr._2
    }
    if (config.measureExchanges) {
      this.exchangesBefore = RxnProfiler.exchangeCounter.sum()
      this.beforeTime = System.nanoTime()
    }
  }

  final override def afterIteration(
    bp: BenchmarkParams,
    ip: IterationParams,
    ir: IterationResult
  ): ju.Collection[_ <: Result[_]] = {
    if (config.measureExchanges) {
      this.afterTime = System.nanoTime()
    }
    val res = new ju.ArrayList[ScalarResult]
    if (config.retriesPerCommit) {
      res.addAll(countRetriesPerCommit())
    }
    if (config.measureExchanges) {
      res.addAll(countExchanges())
    }
    res
  }

  private[this] final def countRetriesPerCommit(): ju.List[ScalarResult] = {
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

  private[this] final def countExchanges(): ju.List[ScalarResult] = {
    val exchangesAfter = RxnProfiler.exchangeCounter.sum()
    val exchangeCount = (exchangesAfter - exchangesBefore).toDouble
    val elapsedTime = this.afterTime - this.beforeTime
    val exchangesPerSecond = if (elapsedTime == 0L) {
      Double.NaN
    } else {
      val elapsedSeconds =
        TimeUnit.SECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS)
      exchangeCount / elapsedSeconds.toDouble
    }
    val res = new ju.ArrayList[ScalarResult]
    if (config.exchangesPerSecond) {
      res.add(
        new ScalarResult(
          RxnProfiler.ExchangesPerSecond,
          exchangesPerSecond,
          RxnProfiler.UnitExchangesPerSecond,
          AggregationPolicy.AVG,
        )
      )
    }
    if (config.exchangeCount) {
      res.add(
        new ScalarResult(
          RxnProfiler.ExchangeCount,
          exchangeCount,
          RxnProfiler.UnitCount,
          AggregationPolicy.SUM,
        )
      )
    }
    res
  }
}

object RxnProfiler {

  final case class Config(
    retriesPerCommit: Boolean,
    exchangesPerSecond: Boolean,
    exchangeCount: Boolean,
  ) {

    def || (that: Config): Config = {
      Config(
        retriesPerCommit = this.retriesPerCommit || that.retriesPerCommit,
        exchangesPerSecond = this.exchangesPerSecond || that.exchangesPerSecond,
        exchangeCount = this.exchangeCount || that.exchangeCount,
      )
    }

    def measureExchanges: Boolean =
      exchangesPerSecond || exchangeCount
  }

  final object Config {

    def debug: Config = Config(
      retriesPerCommit = true,
      exchangesPerSecond = true,
      exchangeCount = true,
    )

    def default: Config = Config(
      retriesPerCommit = true,
      exchangesPerSecond = true,
      exchangeCount = false,
    )
  }

  final val RetriesPerCommit = "rxn.retriesPerCommit"
  final val UnitRetriesPerCommit = "retries/commit"
  final val ExchangesPerSecond = "rxn.exchangesPerSec"
  final val UnitExchangesPerSecond = "xchg/s"
  final val ExchangeCount = "rxn.exchangeCount"
  final val UnitCount = "counts"

  final def profiledExchanger[A, B]: Axn[Exchanger[A, B]] =
    Exchanger.profiled[A, B](this.exchangeCounter)

  private[choam] final val exchangeCounter: LongAdder =
    new LongAdder
}
