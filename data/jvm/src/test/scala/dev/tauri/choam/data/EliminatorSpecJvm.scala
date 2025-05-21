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

import cats.syntax.all._
import cats.effect.IO
import cats.effect.instances.spawn.parallelForGenSpawn

import core.{ Rxn, Axn, Ref }

final class EliminatorSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with EliminatorSpecJvm[zio.Task]

final class EliminatorSpecJvm_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with EliminatorSpecJvm[IO]

trait EliminatorSpecJvm[F[_]] extends EliminatorSpec[F] { this: McasImplSpec =>

  final override def munitTimeout: Duration =
    super.munitTimeout * 2

  private def concurrentPushPopTest(
    tryPopRxn: Axn[Option[Int]],
    pushRxn: Rxn[Int, Unit],
  ): F[Unit] = {
    val k = 4
    for {
      _ <- F.cede
      _ <- pushRxn[F](0)
      res <- F.both(
        List.fill(k)(tryPopRxn.run[F]).parSequence,
        List.tabulate(k)(idx => pushRxn[F](idx + 1)).parSequence_,
      )
      popped = res._1.collect { case Some(i) => i }
      remaining = (k + 1) - popped.size
      maybePopped2 <- tryPopRxn.run[F].replicateA(remaining)
      popped2 = maybePopped2.collect { case Some(i) => i}
      _ <- assertEqualsF(popped2.size, maybePopped2.size)
      set = (popped ++ popped2).toSet
      _ <- assertEqualsF(set.size, popped.size + popped2.size)
      _ <- assertEqualsF(set, (0 to k).toSet)
    } yield ()
  }

  test("EliminationStackForTesting (elimination)") {
    val t = for {
      s <- EliminationStackForTesting[Int].run[F]
      _ <- concurrentPushPopTest(s.tryPop, s.push)
    } yield ()
    t.replicateA_(50000)
  }

  test("EliminationStackForTesting (overlapping descriptors)") {
    for {
      s <- EliminationStackForTesting[Int].run[F]
      ref <- Ref(0).run[F]
      _ <- concurrentPushPopTest(
        // these 2 operations can never exchange
        // with each other, since they both touch
        // `ref` before trying to exchange; but
        // the stack should work correctly nevertheless:
        ref.update(_ + 1) *> s.tryPop,
        (ref.update(_ + 1) × s.push).contramap[Int](i => ((), i)).map(_._2),
      )
    } yield ()
  }

  test("EliminationStack2 (elimination)") {
    val t = for {
      s <- EliminationStack2[Int].run[F]
      _ <- concurrentPushPopTest(s.tryPop, s.push)
    } yield ()
    t.replicateA_(50000)
  }

  test("EliminationStack2 (overlapping descriptors)") {
    for {
      s <- EliminationStack2[Int].run[F]
      ref <- Ref(0).run[F]
      _ <- concurrentPushPopTest(
        // these 2 operations can never exchange
        // with each other, since they both touch
        // `ref` before trying to exchange; but
        // the stack should work correctly nevertheless:
        ref.update(_ + 1) *> s.tryPop,
        (ref.update(_ + 1) × s.push).contramap[Int](i => ((), i)).map(_._2),
      )
    } yield ()
  }
}
