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

import cats.syntax.all._
import cats.effect.IO
import cats.effect.instances.spawn._
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
      fibs <- (1 to N).toList.traverse { idx => (latches(idx - 1).complete(()) *> p.get.commit).start }
      _ <- F.cede
      _ <- latches.traverse_(_.get)
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
      fibs <- (1 to N).toList.traverse { idx => (latches(idx - 1).complete(()) *> p.get.commit).start }
      _ <- F.cede
      _ <- latches.traverse_(_.get)
      _ <- assertResultF(p.tryGet.commit, None)
      toCancel = rng.shuffle(fibs).take(N >> 1)
      results <- F.both(
        (1 to M).toList.parTraverse { i =>
          F.cede *> p.complete(i).commit.map { ok => (i, ok) }
        },
        toCancel.parTraverseVoid(_.cancel)
      ).map(_._1)
      okResults = results.collect { case (i, true) => i }
      _ <- assertEqualsF(okResults.size, 1)
      okIdx = okResults.head
      _ <- assertResultF(p.tryGet.commit, Some(okIdx))
      fibOutcomes <- fibs.traverse(_.join)
      _ <- assertResultF(fibOutcomes.forallM {
        case Outcome.Canceled() => F.pure(true)
        case Outcome.Succeeded(fa) => fa.map { a => (a == okIdx) }
        case Outcome.Errored(err) => F.raiseError(err)
      }, true)
    } yield ()
    t.replicateA_(if (isJs()) 10 else 100)
  }
}
