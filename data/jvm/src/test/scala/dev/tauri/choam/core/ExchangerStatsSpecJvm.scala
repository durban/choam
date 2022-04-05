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
package core

import java.lang.ref.WeakReference

import scala.collection.immutable.{ Map => SMap }

import cats.effect.kernel.Fiber
import cats.effect.IO

final class ExchangerStatsSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with ExchangerStatsSpecJvm[zio.Task]

final class ExchangerStatsSpecJvm_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with ExchangerStatsSpecJvm[IO]

trait ExchangerStatsSpecJvm[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("Statistics") {
    import ExchangerImplJvm.Statistics
    import Exchanger.Params
    val s = Statistics(
      effectiveSize = 32,
      misses = 127,
      spinShift = 21,
      exchanges = 122,
    )
    assertEquals(Statistics.effectiveSize(s), 32.toByte)
    assertEquals(Statistics.misses(s), 127.toByte)
    assertEquals(Statistics.spinShift(s), 21.toByte)
    assertEquals(Statistics.exchanges(s), 122.toByte)
    val s1 = Statistics.withExchanges(s, exchanges = -23)
    assertEquals(Statistics.effectiveSize(s1), 32.toByte)
    assertEquals(Statistics.misses(s1), 127.toByte)
    assertEquals(Statistics.spinShift(s1), 21.toByte)
    assertEquals(Statistics.exchanges(s1), -23.toByte)
    val s2 = Statistics.withMisses(s1, misses = 0)
    assertEquals(Statistics.effectiveSize(s2), 32.toByte)
    assertEquals(Statistics.misses(s2), 0.toByte)
    assertEquals(Statistics.spinShift(s2), 21.toByte)
    assertEquals(Statistics.exchanges(s2), -23.toByte)
    val s3 = Statistics.missed(s2, Params())
    assertEquals(Statistics.effectiveSize(s3), 32.toByte)
    assertEquals(Statistics.misses(s3), 1.toByte)
    assertEquals(Statistics.spinShift(s3), 21.toByte)
    assertEquals(Statistics.exchanges(s3), -23.toByte)
    val s4 = Statistics.missed(
      Statistics.withMisses(s3, misses = Params().maxMisses),
      Params(),
    )
    assertEquals(Statistics.effectiveSize(s4), 16.toByte)
    assertEquals(Statistics.misses(s4), 0.toByte)
    assertEquals(Statistics.spinShift(s4), 21.toByte)
    assertEquals(Statistics.exchanges(s4), -23.toByte)
    val s5 = Statistics.contended(s4, size = core.ExchangerImplJvm.size, p = Params())
    assertEquals(Statistics.effectiveSize(s5), 16.toByte)
    assertEquals(Statistics.misses(s5), -1.toByte)
    assertEquals(Statistics.spinShift(s5), 21.toByte)
    assertEquals(Statistics.exchanges(s5), -23.toByte)
    val s6 = Statistics.contended(
      Statistics.withMisses(s5, misses = Params().minMisses),
      size = 128,
      p = Params(),
    )
    assertEquals(Statistics.effectiveSize(s6), 32.toByte)
    assertEquals(Statistics.misses(s6), 0.toByte)
    assertEquals(Statistics.spinShift(s6), 21.toByte)
    assertEquals(Statistics.exchanges(s6), -23.toByte)
  }

  private[this] final def getStats(ctx: mcas.Mcas.ThreadContext): Option[SMap[AnyRef, AnyRef]] = {
    if (ctx.supportsStatistics) Some(ctx.getStatisticsPlain())
    else None
  }

  private[this] final def startExchange[A, B](ex: Exchanger[A, B], a: A): F[Fiber[F, Throwable, (B, Option[SMap[AnyRef, AnyRef]])]] = {
    F.interruptible {
      val b: B = ex.exchange.unsafePerform(a, this.mcasImpl)
      val ctx = Rxn.unsafe.context(ctx => ctx).unsafeRun(this.mcasImpl)
      (b, getStats(ctx))
    }.start
  }

  test("An Exchanger and its dual must use the same key in a StatMap") {
    for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      _ <- assertSameInstanceF(ex.key, ex.dual.key)
      f1 <- startExchange(ex, "foo")
      f2 <- startExchange(ex.dual, 42)
      r1 <- f1.joinWithNever
      (res1, stats1) = r1
      _ <- assertEqualsF(res1, 42)
      _ <- F.delay {
        stats1 match {
          case Some(stats) =>
            assert(stats.contains(ex.key))
          case None =>
            ()
        }
      }
      r2 <- f2.joinWithNever
      (res2, stats2) = r2
      _ <- assertEqualsF(res2, "foo")
      _ <- F.delay {
        stats2 match {
          case Some(stats) =>
            assert(stats.contains(ex.key))
          case None =>
            ()
        }
      }
    } yield ()
  }

  test("A StatMap must persist between different unsafePerform runs") {
    for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      ex2 <- Rxn.unsafe.exchanger[String, Int].run[F]
      task1 = startExchange(ex, "foo")
      task2 = startExchange(ex2, "bar")
      f1 <- (task1.flatMap(_.joinWithNever), task2.flatMap(_.joinWithNever)).mapN(_ -> _).start
      f2 <- (ex.dual.exchange[F](42), ex2.dual.exchange[F](23)).mapN(_ -> _).start
      r1 <- f1.joinWithNever
      (res11, res12) = r1
      _ <- assertEqualsF(res11._1, 42)
      _ <- assertEqualsF(res12._1, 23)
      _ <- F.delay {
        val stats1 = res11._2
        val stats2 = res12._2
        stats1 match {
          case Some(s1) =>
            assert(s1.contains(ex.key))
            assert(stats2.isDefined)
            assert(stats2.get.contains(ex2.key))
          case None =>
            assert(stats2.isEmpty)
        }
      }
      _ <- assertResultF(f2.joinWithNever, ("foo", "bar"))
    } yield ()
  }

  test("A StatMap must not prevent an Exchanger from being garbage collected") {
    val tsk0 = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- startExchange(ex, "foo")
      f2 <- ex.dual.exchange.apply[F](42).start
      r1 <- f1.joinWithNever
      _ <- assertEqualsF(r1._1, 42)
      _ <- assertResultF(f2.joinWithNever, "foo")
    } yield (new WeakReference(ex), r1._2)

    val tsk1: F[Unit] = tsk0.flatMap { wc =>
      val (weakref, stats) = wc
      stats match {
        case Some(statMap) =>
          F.interruptible {
            var ex = weakref.get()
            if (ex ne null) {
              assert(statMap.contains(ex.key))
            } // else: ok, already collected
            ex = null
            while (weakref.get() ne null) {
              System.gc()
              Thread.sleep(1L)
            }
          }
        case None =>
          F.unit
      }
    }

    this.absolutelyUnsafeRunSync(tsk1)
  }
}
