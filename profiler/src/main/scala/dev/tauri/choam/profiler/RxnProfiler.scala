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
package profiler

import java.{ util => ju }
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

import cats.syntax.all._

import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.infra.{ BenchmarkParams, IterationParams }
import org.openjdk.jmh.results.{ IterationResult, Result, ResultRole, ScalarResult, TextResult, AggregationPolicy, Aggregator }
import org.openjdk.jmh.util.SingletonStatistics

import com.monovore.decline.{ Opts, Command }

import core.Exchanger

import internal.mcas.Consts
import internal.mcas.Mcas.{ Emcas, RetryStats }

/**
 * JMH profiler "plugin" for `Rxn` statistics/measurements.
 *
 * How to use: `-prof dev.tauri.choam.profiler.RxnProfiler`
 *
 * Configuration:
 * `-prof "dev.tauri.choam.profiler.RxnProfiler:opt1;opt2;opt3"`
 *
 * Most statistics are only collected if explicitly enabled
 * with setting the `dev.tauri.choam.stats` system property
 * to `true`.
 *
 * Available options are:
 * - retries (-> rxn.retriesPerCommit)
 * - reusedWeakRefs (-> rxn.reusedWeakRefs)
 * - exchanges (-> rxn.exchangesPerSec)
 * - exchangeCount (-> rxn.exchangeCount)
 * - exchangerStats (-> rxn.exchangerStats)
 *
 * Measurements:
 * - rxn.retriesPerCommit:
 *   average number of retries (not including alternatives) per commit
 * - rxn.reusedWeakRefs: TODO
 * - rxn.exchangesPerSec:
 *   average number of successful exchanges per second (note: this data
 *   is not collected for every exchanger, only for ones created by
 *   `RxnProfiler.profiledExchanger`)
 * - rxn.exchangeCount:
 *   like `rxn.exchangesPerSec`, but reports the count of successful
 *   exchanges
 * - rxn.exchangerStats: TODO
 */
final class RxnProfiler(configLine: String) extends InternalProfiler {

  import RxnProfiler._

  def this() = {
    this("")
  }

  private[this] var statsBefore: RetryStats =
    null

  private[this] var statsAfter: RetryStats =
    null

  private[this] var exchangesBefore: Long =
    0L

  private[this] var timeBefore: Long =
    0L

  private[this] var timeAfter: Long =
    0L

  private[this] var elapsedSeconds: Double =
    0.0

  private[this] val config: Config = {
    val p = Command("rxn", "RxnProfiler") {
      val debug = Opts.flag("debug", short = "d", help = "debug mode").orFalse
      val commits = Opts.flag("commits", help = "commits / sec").orFalse
      val retries = Opts.flag("retries", help = "retries / commit").orFalse
      val tries = Opts.flag("tries", help = "tries / commit").orFalse
      val reusedWr = Opts.flag("reusedWeakRefs", help = "max. number of Refs sharing a weak marker").orFalse
      val exchangesPs = Opts.flag("exchanges", help = "exchanges / sec").orFalse
      val exchangeCount = Opts.flag("exchangeCount", help = "exchange count").orFalse
      val exchangerStats = Opts.flag("exchangerStats", help = "exchanger stats").orFalse
      val cfg = (commits, retries, tries, reusedWr, exchangesPs, exchangeCount, exchangerStats).mapN(Config.apply)
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
    if (config.commitsPerSecond || config.retriesPerCommit) {
      this.statsBefore = Emcas.getRetryStats()
    }
    if (config.measureExchanges) {
      this.exchangesBefore = RxnProfiler.exchangeCounter.sum()
    }
    if (config.commitsPerSecond || config.measureExchanges) {
      this.timeBefore = System.nanoTime()
    }
  }

  final override def afterIteration(
    bp: BenchmarkParams,
    ip: IterationParams,
    ir: IterationResult
  ): ju.Collection[_ <: Result[_]] = {
    val res = new ju.ArrayList[Result[_]]
    this.timeAfter = System.nanoTime()
    this.elapsedSeconds = getElapsedSeconds()
    this.statsAfter = Emcas.getRetryStats()
    if (config.commitsPerSecond) {
      res.addAll(countCommitsPerSecond())
    }
    if (config.retriesPerCommit) {
      res.addAll(countRetriesPerCommit(retries = true))
    }
    if (config.triesPerCommit) {
      res.addAll(countRetriesPerCommit(retries = false))
    }
    if (config.measureExchanges) {
      res.addAll(countExchanges())
    }
    if (config.reusedWeakRefs) {
      res.add(new ScalarResult(
        RxnProfiler.ReusedWeakRefs,
        Emcas.maxReusedWeakRefs().toDouble,
        RxnProfiler.UnitCount,
        AggregationPolicy.MAX,
      ))
    }
    if (!Consts.statsEnabled) {
      res.add(new TextResult(
        s"WARNING: stats are not enabled (if desired, enable them with -D${Consts.statsEnabledProp}=true)",
        "rxn.warning",
      ))
    }
    res
  }

  private[this] final def getElapsedSeconds(): Double = {
    val elapsedTime = this.timeAfter - this.timeBefore
    if (elapsedTime == 0L) {
      0.0
    } else {
      TimeUnit.SECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS).toDouble
    }
  }

  private[this] final def computePerSecond(value: Double): Double = {
    if (this.elapsedSeconds == 0.0) {
      Double.NaN
    } else {
      value / this.elapsedSeconds
    }
  }

  private[this] final def countCommitsPerSecond(): ju.List[ScalarResult] = {
    val commitsAfter = this.statsAfter.commits
    val commits = commitsAfter - this.statsBefore.commits
    val commitsPerSecond = computePerSecond(commits.toDouble)
    ju.List.of(
      new ScalarResult(
        RxnProfiler.CommitsPerSecond,
        commitsPerSecond,
        RxnProfiler.UnitCommitsPerSecond,
        AggregationPolicy.AVG,
      ),
    )
  }

  private[this] final def countRetriesPerCommit(retries: Boolean): ju.List[ScalarResult] = {
    val delta = this.statsAfter - this.statsBefore
    val value = if (retries) delta.avgRetriesPerCommit else delta.avgTriesPerCommit
    ju.List.of(
      new ScalarResult(
        if (retries) RxnProfiler.RetriesPerCommit else RxnProfiler.TriesPerCommit,
        value,
        if (retries) RxnProfiler.UnitRetriesPerCommit else RxnProfiler.UnitTriesPerCommit,
        AggregationPolicy.AVG,
      ),
    )
  }

  private[this] final def countExchanges(): ju.List[Result[_ <: Result[_]]] = {
    val exchangesAfter = RxnProfiler.exchangeCounter.sum()
    val exchangeCount = (exchangesAfter - exchangesBefore).toDouble
    val exchangesPerSecond = computePerSecond(exchangeCount)
    val res = new ju.ArrayList[Result[_]]
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
    if (config.exchangerStats) {
      val stats = Emcas
        .collectExchangerStats()
        .view
        .mapValues { m => m.filter(kv => kv._1 ne Exchanger.paramsKey) }
        .toMap
      res.add(
        new StatsResult(stats :: Nil)
      )
    }
    res
  }
}

// TODO: move to separate file
private final class StatsResult(
  val stats: List[Map[Long, Map[AnyRef, AnyRef]]],
) extends Result[StatsResult](
    ResultRole.SECONDARY,
    RxnProfiler.ExchangerStats,
    new SingletonStatistics(Double.NaN),
    "---",
    AggregationPolicy.SUM,
  ) {

  def this(threadId: Long, s: Map[AnyRef, AnyRef]) =
    this(List(Map(threadId -> s)))

  override def getThreadAggregator(): Aggregator[StatsResult] = { coll =>
    val lb = List.newBuilder[Map[Long, Map[AnyRef, AnyRef]]]
    coll.iterator().forEachRemaining { sr =>
      lb ++= sr.stats
    }
    new StatsResult(lb.result())
  }

  override def getIterationAggregator(): Aggregator[StatsResult] = {
    this.getThreadAggregator()
  }

  override def extendedInfo(): String = {
    val sb = new StringBuilder
    sb ++= "StatsResult(\n"
    this.stats.foreach { s =>
      sb ++= s"  ${s},\n"
    }
    sb ++= s")"
    sb.result()
  }
}

object RxnProfiler {

  final case class Config(
    commitsPerSecond: Boolean,
    retriesPerCommit: Boolean,
    triesPerCommit: Boolean,
    reusedWeakRefs: Boolean,
    exchangesPerSecond: Boolean,
    exchangeCount: Boolean,
    exchangerStats: Boolean,
  ) {

    def || (that: Config): Config = {
      Config(
        commitsPerSecond = this.commitsPerSecond || that.commitsPerSecond,
        retriesPerCommit = this.retriesPerCommit || that.retriesPerCommit,
        triesPerCommit = this.triesPerCommit || that.triesPerCommit,
        reusedWeakRefs = this.reusedWeakRefs || that.reusedWeakRefs,
        exchangesPerSecond = this.exchangesPerSecond || that.exchangesPerSecond,
        exchangeCount = this.exchangeCount || that.exchangeCount,
        exchangerStats = this.exchangerStats || that.exchangerStats,
      )
    }

    def measureExchanges: Boolean =
      exchangesPerSecond || exchangeCount || exchangerStats
  }

  final object Config {

    def debug: Config = Config(
      commitsPerSecond = true,
      retriesPerCommit = true,
      triesPerCommit = true,
      reusedWeakRefs = true,
      exchangesPerSecond = true,
      exchangeCount = true,
      exchangerStats = false,
    )

    def default: Config = Config(
      commitsPerSecond = true,
      retriesPerCommit = false,
      triesPerCommit = true,
      reusedWeakRefs = true,
      exchangesPerSecond = false,
      exchangeCount = false,
      exchangerStats = false,
    )
  }

  final val CommitsPerSecond = "rxn.commitsPerSec"
  final val UnitCommitsPerSecond = "commit/s"
  final val RetriesPerCommit = "rxn.retriesPerCommit"
  final val UnitRetriesPerCommit = "retries/commit"
  final val TriesPerCommit = "rxn.triesPerCommit"
  final val UnitTriesPerCommit = "tries/commit"
  final val ExchangesPerSecond = "rxn.exchangesPerSec"
  final val UnitExchangesPerSecond = "xchg/s"
  final val ExchangeCount = "rxn.exchangeCount"
  final val ExchangerStats = "rxn.exchangerStats"
  final val ReusedWeakRefs = "rxn.reusedWeakRefs"
  final val UnitCount = "counts"

  final def profiledExchanger[A, B]: Axn[Exchanger[A, B]] =
    Exchanger.profiled[A, B](this.exchangeCounter)

  private[choam] final val exchangeCounter: LongAdder =
    new LongAdder
}
