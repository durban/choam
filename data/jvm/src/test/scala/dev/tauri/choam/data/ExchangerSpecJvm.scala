/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
package data

import scala.concurrent.duration._

import cats.effect.{ IO, Outcome }

import core.{ Rxn, Axn, Ref }

final class ExchangerSpecCommon_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with core.ExchangerSpecCommon[zio.Task]

final class ExchangerSpecCommon_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with core.ExchangerSpecCommon[IO]

final class ExchangerSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with ExchangerSpecJvm[zio.Task] {

  final override def zioUnhandledErrorLogLevel =
    zio.LogLevel.Info
}

final class ExchangerSpecJvm_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with ExchangerSpecJvm[IO]

trait ExchangerSpecJvm[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  final val iterations = 8

  private[this] def logOutcome[A](name: String, fa: F[A]): F[A] = {
    fa.guaranteeCase {
      case Outcome.Succeeded(_) => F.delay {
        // println(s"${name} done: ${res}")
      }
      case Outcome.Errored(ex) => F.delay {
        println(s"${name} error: ${ex.getMessage} (${ex.getClass.getName})")
        ex.printStackTrace(System.out)
      }
      case Outcome.Canceled() => F.delay {
        println(s"${name} canceled")
      }
    }
  }

  test("Simple exchange") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- logOutcome("f1", ex.exchange[F]("foo")).start
      f2 <- logOutcome("f2", ex.dual.exchange[F](42)).start
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- assertResultF(f2.joinWithNever, "foo")
    } yield ()
    tsk.replicateA(iterations)
  }

  test("One side transient failure") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- logOutcome("f1", ex.exchange[F]("bar")).start
      ref <- Ref("x").run[F]
      r2 = (
        (ex.dual.exchange * Rxn.unsafe.cas(ref, "-", "y")) + // this will fail
        (ex.dual.exchange * Rxn.unsafe.cas(ref, "x", "y")) // this must succeed
      ).map(_._1)
      f2 <- logOutcome("f2", r2[F](99)).start
      _ <- assertResultF(f1.joinWithNever, 99)
      _ <- assertResultF(f2.joinWithNever, "bar")
    } yield ()
    tsk.replicateA(iterations)
  }

  test("One side doesn't do exchange") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- logOutcome("f1", ex.exchange[F]("baz")).start
      ref <- Ref("x").run[F]
      r2 = (
        (ex.dual.exchange * Rxn.unsafe.cas(ref, "x", "y")) + // this may succeed
        (Rxn.unsafe.cas(ref, "x", "z") * Rxn.unsafe.retry) // no exchange here, but will always fail
      ).map(_._1)
      f2 <- logOutcome("f2", r2[F](64)).start
      _ <- assertResultF(f1.joinWithNever, 64)
      _ <- assertResultF(f2.joinWithNever, "baz")
      _ <- assertResultF(ref.get.run[F], "y")
    } yield ()
    tsk.replicateA(iterations)
  }

  test("Post-commit action can touch the same Refs") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      rxn1 = (r1.update(_ + 1) *> ex.exchange.provide("str")).postCommit(
        r1.update(_ + 1)
      )
      rxn2 = (r2.update(_ + 1) *> ex.dual.exchange.provide(9)).postCommit(
        r2.update(_ + 1)
      )
      f1 <- rxn1.run[F].start
      f2 <- rxn2.run[F].start
      _ <- assertResultF(f1.joinWithNever, 9)
      _ <- assertResultF(f2.joinWithNever, "str")
      _ <- assertResultF(r1.get.run[F], 2)
      _ <- assertResultF(r2.get.run[F], 2)
    } yield ()
    tsk.replicateA(iterations)
  }

  test("Exchange with postCommits on both sides") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1a <- Ref(0).run[F]
      r1b <- Ref(0).run[F]
      r1c <- Ref(0).run[F]
      r1d <- Ref(0).run[F]
      r1e <- Ref(0).run[F]
      r2a <- Ref(0).run[F]
      r2b <- Ref(0).run[F]
      r2c <- Ref(0).run[F]
      r2d <- Ref(0).run[F]
      r2e <- Ref(0).run[F]
      rxn1 = r1a.update(_ + 1).postCommit(r1b.update(_ + 1)) >>> (
        ex.exchange.postCommit(r1c.getAndSet.void).provide("str").flatMapF { (i: Int) =>
          r1d.update(_ + i).postCommit(r1e.update(_ + 1))
        }
      )
      rxn2 = r2a.update(_ + 1).postCommit(r2b.update(_ + 1)) >>> (
        ex.dual.exchange.map(_.length).postCommit(r2c.getAndSet.void).provide(9).flatMapF { (i: Int) =>
          r2d.update(_ + i).postCommit(r2e.update(_ + 1))
        }
      )
      f1 <- rxn1.run[F].start
      f2 <- rxn2.run[F].start
      _ <- f1.joinWithNever
      _ <- f2.joinWithNever
      _ <- assertResultF(r1a.get.run[F], 1) // exactly once
      _ <- assertResultF(r1b.get.run[F], 1) // exactly once
      _ <- assertResultF(r1c.get.run[F], 9) // value from exchange
      _ <- assertResultF(r1d.get.run[F], 9) // value from exchange
      _ <- assertResultF(r1e.get.run[F], 1) // exactly once
      _ <- assertResultF(r2a.get.run[F], 1) // exactly once
      _ <- assertResultF(r2b.get.run[F], 1) // exactly once
      _ <- assertResultF(r2c.get.run[F], 3) // "str".length
      _ <- assertResultF(r2d.get.run[F], 3) // "str".length
      _ <- assertResultF(r2e.get.run[F], 1) // exactly once
    } yield ()
    tsk.replicateA(iterations)
  }

  test("2 Exchangers in 1 Rxn (second never succeeds)") {
    val tsk = for {
      ex1 <- Rxn.unsafe.exchanger[String, Int].run[F]
      ex2 <- Rxn.unsafe.exchanger[String, Int].run[F]
      tsk1 = (ex1.exchange.provide("foo") * ex2.dual.exchange.provide(23).?).run[F]
      tsk2 = (ex1.dual.exchange.provide(42) * ex2.exchange.provide("bar").?).run[F]
      f1 <- logOutcome("f1", F.cede *> tsk1).start
      f2 <- logOutcome("f2", F.cede *> tsk2).start
      // The second exchange will never succeed, since
      // by then the `Rxn`s are joined, and only 1 thread
      // performs them:
      _ <- assertResultF(f1.joinWithNever, (42, None))
      _ <- assertResultF(f2.joinWithNever, ("foo", None))
    } yield ()
    tsk.replicateA(iterations)
  }

  test("2 Exchangers in 1 Rxn (second must succeed)") {
    val tsk = for {
      ex1 <- Rxn.unsafe.exchanger[String, Int].run[F]
      ex2 <- Rxn.unsafe.exchanger[String, Int].run[F]
      tsk1 = (ex1.exchange.provide("foo").? * ex2.dual.exchange.provide(23)).run[F]
      tsk2 = (ex1.dual.exchange.provide(42).? * ex2.exchange.provide("bar")).run[F]
      f1 <- logOutcome("f1", F.cede *> tsk1).start
      f2 <- logOutcome("f2", F.cede *> tsk2).start
      // The second exchange must always succeed,
      // so the first never can (if it tentatively
      // did, it will be forced to retry):
      _ <- assertResultF(f1.joinWithNever, (None, "bar"))
      _ <- assertResultF(f2.joinWithNever, (None, 23))
    } yield ()
    tsk.replicateA(iterations)
  }

  test("Merging of non-disjoint logs must be detected, and cause a retry") {
    def runWithCede[A](rxn: Axn[A]): F[A] = {
      // we'll run the `Rxn`s with CEDE strategy, because otherwise
      // the retrying in a tight loop could cause the timer not to
      // fire, and thus the fallbacks wouldn't start:
      rxn.perform(null, this.mcasImpl, core.RetryStrategy.cede())(F)
    }
    val tsk = for {
      ref <- Ref("abc").run[F]
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      d1 <- F.deferred[Int]
      d2 <- F.deferred[String]
      // these 2 can never exchange with each other,
      // and would retry forever:
      rxn1 = (ref.update(_ + "d") >>> ex.exchange.provide("foo"))
      rxn2 = (ref.update(_ + "x") >>> ex.dual.exchange.provide(42))
      tsk1 = runWithCede(rxn1).guaranteeCase { oc =>
        oc.fold(canceled = d1.complete(-1), errored = _ => d1.complete(-2), completed = _.flatMap(d1.complete)).void
      }
      tsk2 = runWithCede(rxn2).guaranteeCase { oc =>
        oc.fold(canceled = d2.complete("canceled"), errored = _ => d2.complete("errored"), completed = _.flatMap(d2.complete)).void
      }
      // so after a while, we'll start 2 fallbacks,
      // which can exchange with the forever retrying
      // ones, thus making them able to finish:
      fallback1 = ex.dual.exchange.provide(99) // will exchange with `rxn1`
      fallback2 = ex.exchange.provide("bar") // will exchange with `rxn2`
      _ <- F.cede
      results <- F.uncancelable { poll =>
        for {
          tasks <- F.both(tsk1, tsk2).start
          _ <- F.sleep(0.4.second)
          d1During <- d1.tryGet
          d2During <- d2.tryGet
          // ok, let's start the fallbacks; we have to
          // start them one-by-one, otherwise they could
          // exchange with each other, and leave the original
          // tasks running forever:
          fb1Result <- runWithCede(fallback1)
          fb2Result <- runWithCede(fallback2)
          fbResults = (fb1Result, fb2Result)
          // by now, tasks should also be able to finish:
          taskResults <- poll(tasks.joinWithNever)
        } yield (d1During, d2During, fbResults, taskResults)
      }
      (d1During, d2During, fbResults, taskResults) = results
      _ <- assertEqualsF(d1During, None)
      _ <- assertEqualsF(d2During, None)
      _ <- assertEqualsF(fbResults._1, "foo") // value from `rxn1`
      _ <- assertEqualsF(fbResults._2, 42) // value from `rxn2`
      _ <- assertEqualsF(taskResults._1, 99) // value from `fallback1`
      _ <- assertEqualsF(taskResults._2, "bar") // value from `fallback2`
      _ <- assertResultF(d1.get, 99)
      _ <- assertResultF(d2.get, "bar")
    } yield ()
    tsk.replicateA(iterations)
  }
}
