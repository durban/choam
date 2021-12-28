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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.{ IO, Outcome }

import data.EliminationStack

final class ExchangerSpecCommon_EMCAS_AIO
  extends BaseSpecZIO
  with SpecEMCAS
  with ExchangerSpecCommon[zio.Task]

final class ExchangerSpecCommon_EMCAS_BIO
  extends BaseSpecIO
  with SpecEMCAS
  with ExchangerSpecCommon[IO]

final class ExchangerSpecJvm_EMCAS_AIO
  extends BaseSpecZIO
  with SpecEMCAS
  with ExchangerSpecJvm[zio.Task]

final class ExchangerSpecJvm_EMCAS_BIO
  extends BaseSpecIO
  with SpecEMCAS
  with ExchangerSpecJvm[IO]

trait ExchangerSpecJvm[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  final val iterations = 10

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
        (ex.dual.exchange * ref.unsafeCas("-", "y")) + // this will fail
        (ex.dual.exchange * ref.unsafeCas("x", "y")) // this must succeed
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
        (ex.dual.exchange * ref.unsafeCas("x", "y")) + // this may succeed
        (ref.unsafeCas("x", "z") * Rxn.unsafe.retry) // no exchange here, but will always fail
      ).map(_._1)
      f2 <- logOutcome("f2", r2[F](64)).start
      _ <- assertResultF(f1.joinWithNever, 64)
      _ <- assertResultF(f2.joinWithNever, "baz")
      _ <- assertResultF(ref.get.run[F], "y")
    } yield ()
    tsk.replicateA(iterations)
  }

  private[this] def countRetry(@unused label: String, rc: AtomicInteger): Rxn[Any, Unit] = {
    Rxn.unsafe.delay { (_: Any) =>
      val nv = rc.incrementAndGet()
      // println(s"${label} retry count: ${nv}")
      nv
    } >>> Rxn.unsafe.retry[Any, Unit]
  }

  test("delayComputed after exchange") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1a <- Ref(0).run[F]
      r1b <- Ref(0).run[F]
      r1c <- Ref(0).run[F]
      retryCount1 <- F.delay(new AtomicInteger(0))
      r2a <- Ref(0).run[F]
      r2b <- Ref(0).run[F]
      r2c <- Ref(0).run[F]
      retryCount2 <- F.delay(new AtomicInteger(0))
      rxn1 = r1a.update(_ + 1) *> ex.exchange.provide("bar") >>> Rxn.unsafe.delayComputed(Rxn.computed { (i: Int) =>
        r1b.update(_ + 1).as(r1c.update(_ + i))
      }) + countRetry("rxn1", retryCount1)
      rxn2 = r2a.update(_ + 1) *> ex.dual.exchange.provide(42) >>> Rxn.unsafe.delayComputed(Rxn.computed { (s: String) =>
        r2b.update(_ + 1).as(r2c.update(_ + s.length))
      }) + countRetry("rxn2", retryCount2)
      f1 <- logOutcome("f1", rxn1.run[F]).start
      f2 <- logOutcome("f2", rxn2.run[F]).start
      _ <- f1.joinWithNever
      _ <- f2.joinWithNever
      // rxn1:
      _ <- assertResultF(r1a.get.run[F], 1) // exactly once
      rc1 <- F.delay(retryCount1.get())
      _ <- assertF(rc1 >= 0)
      _ <- assertResultF(r1b.get.run[F], rc1 + 1) // at least once (delayComputed may be re-run)
      _ <- assertResultF(r1c.get.run[F], 42) // value from exchange
      // rxn2:
      _ <- assertResultF(r2a.get.run[F], 1) // exactly once
      rc2 <- F.delay(retryCount2.get())
      _ <- assertResultF(r2b.get.run[F], rc2 + 1) // at least once (delayComputed may be re-run)
      _ <- assertResultF(r2c.get.run[F], 3) // "bar".length
    } yield ()
    tsk.replicateA(iterations)
  }

  test("Exchange during delayComputed") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1a <- Ref(0).run[F]
      r1b <- Ref(0).run[F]
      r1c <- Ref(0).run[F]
      r2a <- Ref(0).run[F]
      r2b <- Ref(0).run[F]
      r2c <- Ref(0).run[F]
      rxn1 = r1a.update(_ + 1) >>> Rxn.unsafe.delayComputed(
        r1b.update(_ + 1) *> (
          ex.exchange.provide("foo").map { (i: Int) => r1c.update(_ + i) }
        )
      )
      rxn2 = r2a.update(_ + 1) >>> Rxn.unsafe.delayComputed(
        r2b.update(_ + 1) *> (
          ex.dual.exchange.provide(9).map { (s: String) => r2c.update(_ + s.length) }
        )
      )
      f1 <- rxn1.run[F].start
      f2 <- rxn2.run[F].start
      _ <- f1.joinWithNever
      _ <- f2.joinWithNever
      _ <- assertResultF(r1a.get.run[F], 1) // exactly once
      _ <- assertResultF(r1b.get.run[F], 1) // exactly once
      _ <- assertResultF(r1c.get.run[F], 9) // value from exchange
      _ <- assertResultF(r2a.get.run[F], 1) // exactly once
      _ <- assertResultF(r2b.get.run[F], 1) // exactly once
      _ <- assertResultF(r2c.get.run[F], 3) // "bar".length
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

  test("delayComputed after exchange + postCommit actions on one side") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1a <- Ref(0).run[F]
      r1ap <- Ref(0).run[F]
      r1p <- Ref(0).run[F]
      r1b <- Ref(0).run[F]
      r1bp <- Ref(0).run[F]
      r1c <- Ref(0).run[F]
      r1cp <- Ref(0).run[F]
      retryCount1 <- F.delay(new AtomicInteger(0))
      r2a <- Ref(0).run[F]
      r2b <- Ref(0).run[F]
      r2c <- Ref(0).run[F]
      retryCount2 <- F.delay(new AtomicInteger(0))
      rxn1 = r1a.update(_ + 1).postCommit(r1ap.update(_ + 1)) *> ex.exchange.provide("bar").postCommit(r1p.getAndSet.void) >>> Rxn.unsafe.delayComputed(Rxn.computed { (i: Int) =>
        r1b.update(_ + 1).postCommit(r1bp.update(_ + 1)).as(r1c.update(_ + i).postCommit(r1cp.update(_ + 1)))
      }) + countRetry("rxn1", retryCount1)
      rxn2 = r2a.update(_ + 1) *> ex.dual.exchange.provide(42) >>> Rxn.unsafe.delayComputed(Rxn.computed { (s: String) =>
        r2b.update(_ + 1).as(r2c.update(_ + s.length))
      }) + countRetry("rxn2", retryCount2)
      f1 <- logOutcome("f1", rxn1.run[F]).start
      f2 <- logOutcome("f2", rxn2.run[F]).start
      _ <- f1.joinWithNever
      _ <- f2.joinWithNever
      // rxn1:
      _ <- assertResultF(r1a.get.run[F], 1) // exactly once
      _ <- assertResultF(r1ap.get.run[F], 1) // exactly once
      _ <- assertResultF(r1p.get.run[F], 42) // value from exchange
      rc1 <- F.delay(retryCount1.get())
      _ <- assertF(rc1 >= 0)
      _ <- assertResultF(r1b.get.run[F], rc1 + 1) // at least once (delayComputed may be re-run)
      _ <- assertResultF(r1bp.get.run[F], rc1 + 1) // at least once (delayComputed may be re-run)
      _ <- assertResultF(r1c.get.run[F], 42) // value from exchange
      _ <- assertResultF(r1cp.get.run[F], 1) // exactly once
      // rxn2:
      _ <- assertResultF(r2a.get.run[F], 1) // exactly once
      rc2 <- F.delay(retryCount2.get())
      _ <- assertResultF(r2b.get.run[F], rc2 + 1) // at least once (delayComputed may be re-run)
      _ <- assertResultF(r2c.get.run[F], 3) // "bar".length
    } yield ()
    tsk.replicateA(iterations)
  }

  test("Exchange during delayComputed + postCommit actions on both sides") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      r1a <- Ref(0).run[F]
      r1ap <- Ref(0).run[F]
      r1b <- Ref(0).run[F]
      r1bp1 <- Ref(0).run[F]
      r1bp2 <- Ref(0).run[F]
      r1p <- Ref(0).run[F]
      r1c <- Ref(0).run[F]
      r1cp <- Ref(0).run[F]
      r2a <- Ref(0).run[F]
      r2ap <- Ref(0).run[F]
      r2b <- Ref(0).run[F]
      r2bp1 <- Ref(0).run[F]
      r2bp2 <- Ref(0).run[F]
      r2p <- Ref(0).run[F]
      r2c <- Ref(0).run[F]
      r2cp <- Ref(0).run[F]
      rxn1 = r1a.update(_ + 1).postCommit(r1ap.update(_ + 1)) >>> Rxn.unsafe.delayComputed(
        r1b.update(_ + 1).postCommit(r1bp1.update(_ + 1)).postCommit(r1bp2.update(_ + 1)) *> (
          ex.exchange.postCommit(r1p.getAndSet.void).provide("foo").map { (i: Int) =>
            r1c.update(_ + i).postCommit(r1cp.update(_ + 1))
          }
        )
      )
      rxn2 = r2a.update(_ + 1).postCommit(r2ap.update(_ + 1)) >>> Rxn.unsafe.delayComputed(
        r2b.update(_ + 1).postCommit(r2bp1.update(_ + 1)).postCommit(r2bp2.update(_ + 1)) *> (
          ex.dual.exchange.map(_.length).postCommit(r2p.getAndSet.void).provide(9).map { (i: Int) =>
            r2c.update(_ + i).postCommit(r2cp.update(_ + 1))
          }
        )
      )
      f1 <- rxn1.run[F].start
      f2 <- rxn2.run[F].start
      _ <- f1.joinWithNever
      _ <- f2.joinWithNever
      _ <- assertResultF(r1a.get.run[F], 1) // exactly once
      _ <- assertResultF(r1ap.get.run[F], 1) // exactly once
      _ <- assertResultF(r1b.get.run[F], 1) // exactly once
      _ <- assertResultF(r1bp1.get.run[F], 1) // exactly once
      _ <- assertResultF(r1bp1.get.run[F], 1) // exactly once
      _ <- assertResultF(r1p.get.run[F], 9) // value from exchange
      _ <- assertResultF(r1c.get.run[F], 9) // value from exchange
      _ <- assertResultF(r1cp.get.run[F], 1) // exactly once
      _ <- assertResultF(r2a.get.run[F], 1) // exactly once
      _ <- assertResultF(r2ap.get.run[F], 1) // exactly once
      _ <- assertResultF(r2b.get.run[F], 1) // exactly once
      _ <- assertResultF(r2bp1.get.run[F], 1) // exactly once
      _ <- assertResultF(r2bp2.get.run[F], 1) // exactly once
      _ <- assertResultF(r2p.get.run[F], 3) // "bar".length
      _ <- assertResultF(r2c.get.run[F], 3) // "bar".length
      _ <- assertResultF(r2cp.get.run[F], 1) // exactly once
    } yield ()
    tsk.replicateA(iterations)
  }

  test("An Exchanger and its dual must use the same key in a StatMap".ignore) { // TODO
    for {
      _ <- F.unit
    } yield ()
  }

  test("A StatMap must persist between different unsafePerform runs".ignore) { // TODO
    for {
      _ <- F.unit
    } yield ()
  }

  test("A StatMap must not prevent an Exchanger from being garbage collected".ignore) { // TODO
    for {
      _ <- F.unit
    } yield ()
  }

  test("Elimination") {
    import cats.effect.implicits.parallelForGenSpawn
    import EliminationStack.{ FromStack, Exchanged }
    val N = 512
    val tsk = for {
      elst <- EliminationStack.debug[String].run[F]
      f1 <- List.fill(N)("a").parTraverse { s =>
        elst.pushDebug[F](s)
      }.start
      f2 <- List.fill(N)(elst.tryPopDebug.run[F]).parSequence.start
      pushResults <- f1.joinWithNever
      options <- f2.joinWithNever
      successes = options.count(_.isDefined)
      stackLen <- elst.length.run[F]
      _ <- assertEqualsF(clue(successes) + clue(stackLen), N)
      popResults = options.collect { case Some(r) => r }
      pushExchangeCount <- {
        val pushExchangeCount = pushResults.count {
          case FromStack(_) => false
          case Exchanged(_) => true
        }
        val popExchangeCount = popResults.count {
          case FromStack(_) => false
          case Exchanged(_) => true
        }
        assertEqualsF(pushExchangeCount, popExchangeCount).as(pushExchangeCount)
      }
      _ <- F.delay { println(s"Counted ${pushExchangeCount} exchanges (out of ${N})") }
    } yield pushExchangeCount
    tsk.replicateA(iterations).flatMap { rss =>
      F.delay { println(s"Exchanges: ${rss.sum} / ${N * iterations}") }
    }
  }
}
