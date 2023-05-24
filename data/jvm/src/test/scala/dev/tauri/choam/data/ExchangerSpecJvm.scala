/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.{ IO, Outcome }

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
  with ExchangerSpecJvm[zio.Task]

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

  test("Merging of non-disjoint logs must be detected") {
    val tsk = for {
      ref <- Ref("abc").run[F]
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      rxn1 = (ref.update(_ + "d") >>> ex.exchange.provide("foo"))
      rxn2 = (ref.update(_ + "x") >>> ex.dual.exchange.provide(42))
      tsk1 = F.interruptible { rxn1.unsafeRun(this.mcasImpl) }
      tsk2 = F.interruptible { rxn2.unsafeRun(this.mcasImpl) }
      // one of them must fail, the other one unfortunately
      // will retry forever, so we need to interrupt it:
      r <- F.raceOutcome(tsk1, tsk2)
      _ <- r match {
        case Left(oc) => assertF(oc.isError)
        case Right(oc) => assertF(oc.isError)
      }
      _ <- assertResultF(ref.unsafeDirectRead.run[F], "abc")
    } yield ()
    tsk.replicateA(iterations)
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
      stackLen <- elst.size.run[F]
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
