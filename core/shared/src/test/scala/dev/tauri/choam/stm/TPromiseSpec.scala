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
package stm

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import cats.syntax.all._
import cats.effect.IO
import cats.effect.kernel.Outcome

final class TPromiseSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with TPromiseSpec[IO]

trait TPromiseSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  test("get before complete") {
    val N = 1024
    val M = 8
    val t = for {
      p <- TPromise[Int].commit
      latches <- F.deferred[Unit].replicateA(N)
      fibs <- (0 until N).toList.traverse { idx => (latches(idx).complete(()) *> p.get.commit).start }
      _ <- latches.traverse_(_.get)
      _ <- F.sleep(10.millis)
      _ <- assertResultF(p.tryGet.commit, None)
      results <- (1 to M).toList.parTraverse { i =>
        F.cede *> p.complete(i).commit.map { ok => (i, ok) }
      }
      okResults = results.collect { case (i, true) => i }
      _ <- assertEqualsF(okResults.size, 1)
      okIdx = okResults.head
      _ <- assertResultF(p.tryGet.commit, Some(okIdx))
      fibResults <- fibs.traverse(_.joinWithNever)
      _ <- assertEqualsF(fibResults, List.fill(N)(okIdx))
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }

  test("complete/cancel race") {
    val N = 1024
    val M = 16
    val rng = new scala.util.Random(ThreadLocalRandom.current().nextLong())
    val t = for {
      p <- TPromise[Int].commit
      latches <- F.deferred[Unit].replicateA(N)
      fibs <- (0 until N).toList.traverse { idx => (latches(idx).complete(()) *> p.get.commit).start }
      _ <- latches.traverse_(_.get)
      _ <- F.sleep(10.millis)
      _ <- assertResultF(p.tryGet.commit, None)
      toCancel = rng.shuffle(fibs).take(N >> 1)
      results <- F.both(
        toCancel.parTraverseVoid(_.cancel),
        (1 to M).toList.parTraverse { i =>
          F.cede *> p.complete(i).commit.map { ok => (i, ok) }
        },
      ).map(_._2)
      okResults = results.collect { case (i, true) => i }
      _ <- assertEqualsF(okResults.size, 1)
      okIdx = okResults.head
      _ <- assertResultF(p.tryGet.commit, Some(okIdx))
      fibOutcomes <- fibs.traverse { fib =>
        fib.join.map { oc => (oc, toCancel.contains(fib)) }
      }
      _ <- assertResultF(fibOutcomes.forallM { case (oc, wasCancelled) =>
        oc match {
          case Outcome.Canceled() => F.pure(wasCancelled)
          case Outcome.Succeeded(fa) => fa.map { a => (clue(a) == okIdx) }
          case Outcome.Errored(err) => F.raiseError(err)
        }
      }, true)
      someWasCancelled = if (isJvm()) {
        fibOutcomes.exists(_._1.isCanceled)
      } else {
        true // SN scheduling is too different; JS doesn't really matter here
      }
    } yield someWasCancelled
    t.replicateA(if (isJs()) 10 else 200).flatMap { cancellations =>
      assertF(cancellations.exists(ok => ok))
    }
  }

  test("complete left side of orElse") {
    val t = orElseTest(leftSide = true, N = 1024, M = 16)
    t.replicateA(if (isJs()) 10 else 200).flatMap { cancellations =>
      assertF(cancellations.exists(ok => ok))
    }
  }

  test("complete right side of orElse") {
    val t = orElseTest(leftSide = false, N = 1024, M = 16)
    t.replicateA(if (isJs()) 10 else 200).flatMap { cancellations =>
      assertF(cancellations.exists(ok => ok))
    }
  }

  private[this] def orElseTest(leftSide: Boolean, N: Int, M: Int): F[Boolean] = {
    val rng = new scala.util.Random(ThreadLocalRandom.current().nextLong())
    for {
      p1 <- TPromise[Int].commit
      p2 <- TPromise[Int].commit
      latches <- F.deferred[Unit].replicateA(N)
      fibs <- (0 until N).toList.traverse { idx =>
        (latches(idx).complete(()) *> (p1.get orElse p2.get).commit).start
      }
      _ <- latches.traverse_(_.get)
      _ <- F.sleep(10.millis)
      _ <- assertResultF(p1.tryGet.commit, None)
      _ <- assertResultF(p2.tryGet.commit, None)
      p = if (leftSide) p1 else p2
      other = if (leftSide) p2 else p1
      toCancel =  rng.shuffle(fibs).take(N >> 1)
      results <- F.both(
        toCancel.parTraverseVoid(_.cancel),
        (1 to M).toList.parTraverse { i =>
          F.cede *> p.complete(i).commit.map { ok => (i, ok) }
        },
      ).map(_._2)
      okResults = results.collect { case (i, true) => i }
      _ <- assertEqualsF(okResults.size, 1)
      okIdx = okResults.head
      _ <- assertResultF(p.tryGet.commit, Some(okIdx))
      _ <- assertResultF(other.tryGet.commit, None)
      fibOutcomes <- fibs.traverse { fib =>
        fib.join.map { oc => (oc, toCancel.contains(fib)) }
      }
      _ <- assertResultF(fibOutcomes.forallM { case (oc, wasCancelled) =>
        oc match {
          case Outcome.Canceled() => F.pure(wasCancelled)
          case Outcome.Succeeded(fa) => fa.map { a => (a == okIdx) }
          case Outcome.Errored(err) => F.raiseError(err)
        }
      }, true)
      someWasCancelled = if (isJvm()) {
        fibOutcomes.exists(_._1.isCanceled)
      } else {
        true // SN scheduling is too different; JS doesn't really matter here
      }
      _ <- assertResultF(p.tryGet.commit, Some(okIdx))
      _ <- assertResultF(other.tryGet.commit, None)
      _ <- other.complete(42).commit
      _ <- assertResultF(p.tryGet.commit, Some(okIdx))
      _ <- assertResultF(other.tryGet.commit, Some(42))
    } yield someWasCancelled
  }
}
