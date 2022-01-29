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
package bench
package util

import cats.effect.IO

import munit.CatsEffectSuite

import org.openjdk.jmh.results.Result

final class RxnProfilerSpecIO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnProfilerSpec[IO]

trait RxnProfilerSpec[F[_]] extends CatsEffectSuite with BaseSpecAsyncF[F] { this: KCASImplSpec =>

  def simulateStart(config: String = "debug"): F[RxnProfiler] = F.delay {
    val p = new RxnProfiler(config)
    p.beforeIteration(null, null) // TODO: nulls
    p
  }

  def simulateEnd(p: RxnProfiler): F[Map[String, Result[_]]] = F.delay {
    import scala.jdk.CollectionConverters._
    val rss = p.afterIteration(null, null, null) // TODO: nulls
    Map(rss.asScala.toList.map { r =>
      (r.getLabel -> r)
    }: _*)
  }

  def simulateRun[A](use: RxnProfiler => F[A])(
    check: Map[String, Result[_]] => F[Unit]
  ): F[A] = {
    simulateRunConfig(config = "debug")(use = use)(check = check)
  }

  def simulateRunConfig[A](config: String)(use: RxnProfiler => F[A])(
    check: Map[String, Result[_]] => F[Unit]
  ): F[A] = {
    F.bracket(acquire = simulateStart(config))(use = use)(release = { p =>
      simulateEnd(p).flatMap { results => check(results) }
    })
  }

  def runInFiber[A](tsk: Axn[A]): F[A] = {
    tsk.run[F].start.flatMap { fib => fib.joinWithNever }
  }

  test("baseline") {
    simulateRun { _ => F.unit } { r =>
      for {
        _ <- assertEqualsF(r.size, 6)
        _ <- assertF(r(RxnProfiler.RetriesPerCommit).getScore.isNaN)
        _ <- assertF(r(RxnProfiler.RetriesPerCommitFull).getScore.isNaN)
        _ <- assertF(r(RxnProfiler.RetriesPerCommitMcas).getScore.isNaN)
        _ <- assertEqualsF(r(RxnProfiler.ReusedWeakRefs).getScore, 0.0)
        _ <- assertEqualsF(r(RxnProfiler.ExchangeCount).getScore, 0.0)
        _ <- assertF(r(RxnProfiler.ExchangesPerSecond).getScore.isNaN)
      } yield ()
    }
  }

  test("config") {
    for {
      _ <- simulateRunConfig("") { _ => F.unit } { r => F.delay {
        assert(r.get(RxnProfiler.RetriesPerCommit).isDefined)
        assert(r.get(RxnProfiler.ExchangesPerSecond).isDefined)
        assert(r.get(RxnProfiler.ExchangeCount).isEmpty)
      }}
      _ <- simulateRunConfig("debug") { _ => F.unit } { r => F.delay {
        assert(r.get(RxnProfiler.RetriesPerCommit).isDefined)
        assert(r.get(RxnProfiler.ReusedWeakRefs).isDefined)
        assert(r.get(RxnProfiler.ExchangesPerSecond).isDefined)
        assert(r.get(RxnProfiler.ExchangeCount).isDefined)
      }}
      _ <- simulateRunConfig("retries") { _ => F.unit } { r => F.delay {
        assert(r.get(RxnProfiler.RetriesPerCommit).isDefined)
        assert(r.get(RxnProfiler.ExchangesPerSecond).isEmpty)
        assert(r.get(RxnProfiler.ExchangeCount).isEmpty)
      }}
      _ <- simulateRunConfig("retries;reusedWeakRefs") { _ => F.unit } { r => F.delay {
        assert(r.get(RxnProfiler.RetriesPerCommit).isDefined)
        assert(r.get(RxnProfiler.ReusedWeakRefs).isDefined)
        assert(r.get(RxnProfiler.ExchangesPerSecond).isEmpty)
        assert(r.get(RxnProfiler.ExchangeCount).isEmpty)
      }}
      _ <- simulateRunConfig("retries;exchanges") { _ => F.unit } { r => F.delay {
        assert(r.get(RxnProfiler.RetriesPerCommit).isDefined)
        assert(r.get(RxnProfiler.ReusedWeakRefs).isEmpty)
        assert(r.get(RxnProfiler.ExchangesPerSecond).isDefined)
        assert(r.get(RxnProfiler.ExchangeCount).isEmpty)
      }}
      _ <- simulateRunConfig("retries;exchangeCount") { _ => F.unit } { r => F.delay {
        assert(r.get(RxnProfiler.RetriesPerCommit).isDefined)
        assert(r.get(RxnProfiler.ReusedWeakRefs).isEmpty)
        assert(r.get(RxnProfiler.ExchangesPerSecond).isEmpty)
        assert(r.get(RxnProfiler.ExchangeCount).isDefined)
      }}
    } yield ()
  }

  test("rxn.retriesPerCommit") {
    for {
      // no retry:
      _ <- simulateRun { _ => runInFiber(Rxn.pure(42)) } { r =>
        assertEqualsF(r(RxnProfiler.RetriesPerCommit).getScore, 0.0) *> (
          assertEqualsF(r(RxnProfiler.RetriesPerCommitFull).getScore, 0.0) *> (
            assertEqualsF(r(RxnProfiler.RetriesPerCommitMcas).getScore, 0.0)
          )
        )
      }
      // alts also count:
      _ <- simulateRun { _ => runInFiber(Rxn.unsafe.retry + Rxn.pure(42)) } { r =>
        assertEqualsF(r(RxnProfiler.RetriesPerCommit).getScore, 1.0) *> (
          assertEqualsF(r(RxnProfiler.RetriesPerCommitFull).getScore, 1.0) *> (
            assertEqualsF(r(RxnProfiler.RetriesPerCommitMcas).getScore, 0.0)
          )
        )
      }
      _ <- simulateRun { _ =>
        runInFiber(Rxn.unsafe.retry + (Rxn.unsafe.retry + Rxn.pure(42)))
      } { r =>
        assertEqualsF(r(RxnProfiler.RetriesPerCommit).getScore, 2.0) *> (
          assertEqualsF(r(RxnProfiler.RetriesPerCommitFull).getScore, 2.0) *> (
            assertEqualsF(r(RxnProfiler.RetriesPerCommitMcas).getScore, 0.0)
          )
        )
      }
    } yield ()
  }

  test("rxn.exchanges") {
    for {
      e <- RxnProfiler.profiledExchanger[String, Int].run[F]
      p <- simulateStart()
      fib <- e.exchange[F]("foo").start
      _ <- assertResultF(e.dual.exchange[F](42), "foo")
      _ <- assertResultF(fib.joinWithNever, 42)
      r <- simulateEnd(p)
      _ <- assertEqualsF(r(RxnProfiler.ExchangeCount).getScore, 1.0)
    } yield ()
  }
}
