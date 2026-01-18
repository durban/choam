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

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

import cats.effect.{ IO, Outcome }

import core.{ Rxn, Ref }

final class ExchangerSpecCommon_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with core.ExchangerSpecCommon[IO]

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
      f1 <- logOutcome("f1", ex.exchange("foo").run[F]).start
      f2 <- logOutcome("f2", ex.dual.exchange(42).run[F]).start
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- assertResultF(f2.joinWithNever, "foo")
    } yield ()
    tsk.replicateA_(iterations)
  }

  test("One side transient failure") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- logOutcome("f1", ex.exchange("bar").run[F]).start
      ref <- Ref("x").run[F]
      r2 = (
        (ex.dual.exchange(99) <* Rxn.unsafe.cas(ref, "-", "y")) + // this will fail
        (ex.dual.exchange(99) <* Rxn.unsafe.cas(ref, "x", "y")) // this must succeed
      )
      f2 <- logOutcome("f2", r2.run[F]).start
      _ <- assertResultF(f1.joinWithNever, 99)
      _ <- assertResultF(f2.joinWithNever, "bar")
    } yield ()
    tsk.replicateA_(iterations)
  }

  test("One side doesn't do exchange") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- logOutcome("f1", ex.exchange("baz").run[F]).start
      ref <- Ref("x").run[F]
      r2 = (
        (ex.dual.exchange(64) * Rxn.unsafe.cas(ref, "x", "y")) + // this may succeed
        (Rxn.unsafe.cas(ref, "x", "z") * Rxn.unsafe.retry) // no exchange here, but will always fail
      ).map(_._1)
      f2 <- logOutcome("f2", r2.run[F]).start
      _ <- assertResultF(f1.joinWithNever, 64)
      _ <- assertResultF(f2.joinWithNever, "baz")
      _ <- assertResultF(ref.get.run[F], "y")
    } yield ()
    tsk.replicateA_(iterations)
  }

  test("Post-commit action can touch the same Refs") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      rxn1 = (r1.update(_ + 1) *> ex.exchange("str")).postCommit(
        r1.update(_ + 1)
      )
      rxn2 = (r2.update(_ + 1) *> ex.dual.exchange(9)).postCommit(
        r2.update(_ + 1)
      )
      f1 <- rxn1.run[F].start
      f2 <- rxn2.run[F].start
      _ <- assertResultF(f1.joinWithNever, 9)
      _ <- assertResultF(f2.joinWithNever, "str")
      _ <- assertResultF(r1.get.run[F], 2)
      _ <- assertResultF(r2.get.run[F], 2)
    } yield ()
    tsk.replicateA_(iterations)
  }

  test("Exchange with postCommits on both sides") {
    val  N = 10
    val tsk = for {
      _ <- F.unit
      ex <- F.delay(core.Exchanger.unsafe[String, Int])
      r1a <- Ref(0).run // 0 -> 1
      r1b <- Ref(0).run // PC
      r1c <- Ref(0).run // PC
      r1d <- Ref(0).run // 0 -> 9
      r1e <- Ref(0).run // PC
      r2a <- Ref(0).run // 0 -> 1
      r2b <- Ref(0).run // PC
      r2c <- Ref(0).run // PC
      r2d <- Ref(0).run // 0 -> 3
      r2e <- Ref(0).run // PC
      rxn1 = r1a.update(_ + 1).postCommit(r1b.update(_ + 1)) *> (
        ex.exchange("str").postCommit(x => r1c.getAndSet(x).void).flatMap { (i: Int) =>
          r1d.update(_ + i).postCommit(r1e.update(_ + 1))
        }
      )
      rxn2 = r2a.update(_ + 1).postCommit(r2b.update(_ + 1)) *> (
        ex.dual.exchange(9).map(_.length).postCommit(x => r2c.getAndSet(x).void).flatMap { (i: Int) =>
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
    tsk.replicateA_(N)
  }

  test("2 Exchangers in 1 Rxn (second never succeeds)") {
    val tsk = for {
      ex1 <- Rxn.unsafe.exchanger[String, Int].run[F]
      ex2 <- Rxn.unsafe.exchanger[String, Int].run[F]
      tsk1 = (ex1.exchange("foo") * ex2.dual.exchange(23).?).run[F]
      tsk2 = (ex1.dual.exchange(42) * ex2.exchange("bar").?).run[F]
      f1 <- logOutcome("f1", F.cede *> tsk1).start
      f2 <- logOutcome("f2", F.cede *> tsk2).start
      // The second exchange will never succeed, since
      // by then the `Rxn`s are joined, and only 1 thread
      // performs them:
      _ <- assertResultF(f1.joinWithNever, (42, None))
      _ <- assertResultF(f2.joinWithNever, ("foo", None))
    } yield ()
    tsk.replicateA_(iterations)
  }

  test("2 Exchangers in 1 Rxn (second must succeed)") {
    val tsk = for {
      ex1 <- Rxn.unsafe.exchanger[String, Int].run[F]
      ex2 <- Rxn.unsafe.exchanger[String, Int].run[F]
      tsk1 = (ex1.exchange("foo").? * ex2.dual.exchange(23)).run[F]
      tsk2 = (ex1.dual.exchange(42).? * ex2.exchange("bar")).run[F]
      f1 <- logOutcome("f1", F.cede *> tsk1).start
      f2 <- logOutcome("f2", F.cede *> tsk2).start
      // The second exchange must always succeed,
      // so the first never can (if it tentatively
      // did, it will be forced to retry):
      _ <- assertResultF(f1.joinWithNever, (None, "bar"))
      _ <- assertResultF(f2.joinWithNever, (None, 23))
    } yield ()
    tsk.replicateA_(iterations)
  }

  test("Merging of non-disjoint logs must be detected, and cause a retry") {
    def runWithCede[A](rxn: Rxn[A]): F[A] = {
      // we'll run the `Rxn`s with CEDE strategy, because otherwise
      // the retrying in a tight loop could cause the timer not to
      // fire, and thus the fallbacks wouldn't start:
      rxn.perform(this.runtime, RetryStrategy.cede())(using F)
    }
    val tsk = for {
      ref <- Ref("abc").run[F]
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      d1 <- F.deferred[Int]
      d2 <- F.deferred[String]
      // these 2 can never exchange with each other,
      // and would retry forever:
      rxn1 = (ref.update(_ + "d") *> ex.exchange("foo"))
      rxn2 = (ref.update(_ + "x") *> ex.dual.exchange(42))
      tsk1 = runWithCede(rxn1).guaranteeCase { oc =>
        oc.fold(canceled = d1.complete(-1), errored = _ => d1.complete(-2), completed = _.flatMap(d1.complete)).void
      }
      tsk2 = runWithCede(rxn2).guaranteeCase { oc =>
        oc.fold(canceled = d2.complete("canceled"), errored = _ => d2.complete("errored"), completed = _.flatMap(d2.complete)).void
      }
      // so after a while, we'll start 2 fallbacks,
      // which can exchange with the forever retrying
      // ones, thus making them able to finish:
      fallback1 = ex.dual.exchange(99) // will exchange with `rxn1`
      fallback2 = ex.exchange("bar") // will exchange with `rxn2`
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
    tsk.replicateA_(iterations)
  }

  test("The 2 `Rxn`s run with separated logs") {
    val maxRetries = Some(4096)
    val str = RetryStrategy.Default.withCede.withMaxRetries(maxRetries)
    for {
      ref <- Ref(0).run[F]
      leftReceived <- F.delay(new AtomicInteger(-1))
      rightReceived <- F.delay(new AtomicInteger(-1))
      countBgWrites <- F.delay(new AtomicInteger(0))
      ex <- Rxn.unsafe.exchanger[Int, Int].run[F]
      // as they run with separated logs, they should be
      // able to (tentatively) update the same ref, and
      // see their own writes; however, the conflict must
      // be detected later, and they must never commit
      left = (ref.updateAndGet(_ + 1) * ex.exchange(42)).flatMap { case (v1, i) =>
        leftReceived.set(i)
        ref.get.flatMap { v2 =>
          assertEquals(v2, v1) // it should see it's own write
          ref.set(v2 + 1)
        }
      }
      right = (ref.updateAndGet(_ + 99) * ex.dual.exchange(123)).flatMap { case (v1, i) =>
        rightReceived.set(i)
        ref.get.flatMap { v2 =>
          assertEquals(v2, v1) // it should see it's own write
          ref.set(v2 + 100)
        }
      }
      taskLeft = left.perform[F, Unit](this.runtime, str)(using this.F)
      taskRight = right.perform[F, Unit](this.runtime, str)(using this.F)
      backgroundTask = F.uncancelable(_ => ref.update(_ + 1).run[F] *> {
        F.delay { countBgWrites.incrementAndGet(); () }
      }) *> F.sleep(0.002.seconds).foreverM[Unit]
      fib <- backgroundTask.start
      r <- F.both(taskLeft.attempt, taskRight.attempt)
      _ <- fib.cancel
      _ <- r._1 match {
        case Left(ex) => assertF(ex.isInstanceOf[Rxn.MaxRetriesExceeded], s"unexpected error (left): ${ex}")
        case Right(r) => failF(s"unexpected success (left): $r")
      }
      _ <- r._2 match {
        case Left(ex) => assertF(ex.isInstanceOf[Rxn.MaxRetriesExceeded], s"unexpected error (right): ${ex}")
        case Right(r) => failF(s"unexpected success (right): $r")
      }
      leftRec <- F.delay(leftReceived.get())
      rightRec <- F.delay(rightReceived.get())
      // Note: there is a chance that the 2 threads never even meet,
      // and no exchange happens at all, so -1 is allowed here.
      _ <- assertF((clue(leftRec) == 123) || (leftRec == -1))
      _ <- assertF((clue(rightRec) == 42) || (rightRec == -1))
      _ <- assertResultF(ref.get.run[F], countBgWrites.get())
    } yield ()
  }
}
