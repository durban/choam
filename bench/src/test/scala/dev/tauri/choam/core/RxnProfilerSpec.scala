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
package core

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.instances.spawn.parallelForGenSpawn
import cats.syntax.all._

import munit.CatsEffectSuite

import org.openjdk.jmh.results.Result

final class RxnProfilerSpecIO
  extends BaseSpecIO
  with SpecEmcas
  with RxnProfilerSpec[IO]

trait RxnProfilerSpec[F[_]] extends CatsEffectSuite with BaseSpecAsyncF[F] { this: McasImplSpec =>

  def simulateStart(config: String = "debug"): F[RxnProfiler] = F.delay {
    val p = new RxnProfiler(config)
    p.beforeIteration(null, null)
    p
  }

  def simulateEnd(p: RxnProfiler): F[Map[String, Result[_]]] = F.delay {
    import scala.jdk.CollectionConverters._
    val rss = p.afterIteration(null, null, null)
    Map[String, Result[_]](rss.asScala.toList.map { r =>
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
        eps = r(RxnProfiler.ExchangesPerSecond).getScore
        _ <- assertF(eps.isNaN || (eps == 0.0d))
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
    def succeedAfter(after: Int, optRef: Option[Ref[Int]] = None): F[Axn[Int]] = {
      F.delay(new AtomicInteger).map { ctr =>
        Axn.unsafe.delay { ctr.getAndIncrement() }.flatMapF { retries =>
          if (retries >= after) {
            optRef match {
              case Some(ref) => ref.getAndUpdate(_ + 1)
              case None => Axn.pure(42)
            }
          } else {
            Rxn.unsafe.retry
          }
        }
      }
    }
    for {
      // no retry:
      r0 <- succeedAfter(0)
      _ <- simulateRun { _ => runInFiber(r0) } { r =>
        assertEqualsF(r(RxnProfiler.RetriesPerCommit).getScore, 0.0) *> (
          assertEqualsF(r(RxnProfiler.RetriesPerCommitFull).getScore, 0.0) *> (
            assertEqualsF(r(RxnProfiler.RetriesPerCommitMcas).getScore, 0.0)
          )
        )
      }
      // 1 retry:
      r1 <- succeedAfter(1)
      _ <- simulateRun { _ => runInFiber(r1) } { r =>
        assertEqualsF(r(RxnProfiler.RetriesPerCommit).getScore, 1.0) *> (
          assertEqualsF(r(RxnProfiler.RetriesPerCommitFull).getScore, 1.0) *> (
            assertEqualsF(r(RxnProfiler.RetriesPerCommitMcas).getScore, 0.0)
          )
        )
      }
      // 5 retries:
      r5 <- succeedAfter(5)
      _ <- simulateRun { _ => runInFiber(r5) } { r =>
        assertEqualsF(r(RxnProfiler.RetriesPerCommit).getScore, 5.0) *> (
          assertEqualsF(r(RxnProfiler.RetriesPerCommitFull).getScore, 5.0) *> (
            assertEqualsF(r(RxnProfiler.RetriesPerCommitMcas).getScore, 0.0)
          )
        )
      }
      // parallel runs, there should be additional retries:
      _ <- if (this.isJvm()) {
        def mkABC(ref: Ref[Int]): (F[Int], F[Int], F[Int]) = {
          (
            succeedAfter(2, Some(ref)).flatMap(_.run[F]),
            succeedAfter(2, Some(ref)).flatMap(_.run[F]),
            succeedAfter(2, Some(ref)).flatMap(_.run[F])
          )
        }
        for {
          ref <- Ref(0).run[F]
          tasks <- F.delay(mkABC(ref)).replicateA(100)
          tsk = tasks.parTraverse_ {
            case (rpA, rpB, rpC) =>
              F.both(rpA, F.both(rpB, rpC))
          }
          _ <- simulateRun { _ => tsk.start.flatMap(_.joinWithNever) } { r =>
            F.delay {
              // there should be sometimes additional retries (due to concurrency):
              val retries = r(RxnProfiler.RetriesPerCommit).getScore
              assert(retries > 2.0)
              // TODO: check MCAS retries (they're currently always 0)
            }
          }
        } yield ()
      } else {
        F.unit
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
