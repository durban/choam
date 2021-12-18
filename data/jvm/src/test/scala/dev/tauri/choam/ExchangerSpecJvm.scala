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

import data.TreiberStack

final class ExchangerSpecCommon_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with ExchangerSpecCommon[IO]

final class ExchangerSpecCommon_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with ExchangerSpecCommon[zio.Task]

final class ExchangerSpecJvm_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with ExchangerSpecJvm[IO]

final class ExchangerSpecJvm_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with ExchangerSpecJvm[zio.Task]

trait ExchangerSpecJvm[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  final val iterations = 10

  private[this] def logOutcome[A](name: String, fa: F[A]): F[A] = {
    fa.guaranteeCase {
      case Outcome.Succeeded(res) => F.delay {
        println(s"${name} done: ${res}")
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

  test("delayComputed after exchange") {
    def logRetry(label: String, rc: AtomicInteger): Rxn[Any, Unit] = {
      Rxn.unsafe.delay[Any, Unit] { _ =>
        val nv = rc.incrementAndGet()
        println(s"${label} retry count: ${nv}")
      } >>> Rxn.unsafe.retry[Any, Unit]
    }
    for {
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
      }) + logRetry("rxn1", retryCount1)
      rxn2 = r2a.update(_ + 1) *> ex.dual.exchange.provide(42) >>> Rxn.unsafe.delayComputed(Rxn.computed { (s: String) =>
        r2b.update(_ + 1).as(r2c.update(_ + s.length))
      }) + logRetry("rxn2", retryCount2)
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
  }

  test("Elimination") {
    import cats.effect.implicits.parallelForGenSpawn
    sealed abstract class Result[A]
    final case class FromStack[A](a: A) extends Result[A]
    final case class Exchanged[A](a: A) extends Result[A]
    val N = 512
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Any].run[F]
      st <- TreiberStack[String].run[F]
      push = (
        st.push.map(FromStack(_)) + ex.exchange.as(Exchanged(()))
      ) : Rxn[String, Result[Unit]]
      tryPop = (
        (
          st.unsafePop.map[Result[String]](FromStack(_)) +
          ex.dual.exchange.map(Exchanged(_))
        ).?
      ) : Axn[Option[Result[String]]]
      f1 <- List.fill(N)("a").parTraverse { s =>
        push[F](s)
      }.start
      f2 <- List.fill(N)(tryPop.run[F]).parSequence.start
      pushResults <- f1.joinWithNever
      options <- f2.joinWithNever
      successes = options.count(_.isDefined)
      stackLen <- st.length.run[F]
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
