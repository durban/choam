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

import core.{ Rxn, Ref, Eliminator }

final class EliminatorSpecJvm_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with EliminatorSpecJvm[IO]

trait EliminatorSpecJvm[F[_]] extends EliminatorSpec[F] { this: McasImplSpec =>

  final override def munitTimeout: Duration =
    super.munitTimeout * 2

  private def concurrentPushPopTest(
    tryPopRxn: Rxn[Option[Int]],
    pushRxn: Int => Rxn[Unit],
  ): F[Unit] = {
    val k = 4
    for {
      _ <- F.cede
      _ <- pushRxn(0).run[F]
      res <- F.both(
        List.fill(k)(tryPopRxn.run[F]).parSequence,
        List.tabulate(k)(idx => pushRxn(idx + 1).run[F]).parSequence_,
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
        i => (ref.update(_ + 1) * s.push(i)).map(_._2),
      )
    } yield ()
  }

  test("EliminationStack (elimination)") {
    val t = for {
      s <- EliminationStack[Int].run[F]
      _ <- concurrentPushPopTest(s.tryPop, s.push)
    } yield ()
    t.replicateA_(this.platform match {
      case MUnitUtils.Jvm => 50000
      case MUnitUtils.Js => 50
      case MUnitUtils.Native => 5000
    })
  }

  test("EliminationStack (overlapping descriptors)") {
    for {
      s <- EliminationStack[Int].run[F]
      ref <- Ref(0).run[F]
      _ <- concurrentPushPopTest(
        // these 2 operations can never exchange
        // with each other, since they both touch
        // `ref` before trying to exchange; but
        // the stack should work correctly nevertheless:
        ref.update(_ + 1) *> s.tryPop,
        i => (ref.update(_ + 1) * s.push(i)).map(_._2),
      )
    } yield ()
  }

  test("Eliminator.tagged (parallel)") {
    val t = for {
      ctr1 <- Ref(0).run[F]
      ctr2 <- Ref(0).run[F]
      e <- Eliminator.tagged[String, Int, Int, String](
        s => Rxn.pure(s.toInt).flatTap(_ => ctr1.update(_ + 1)),
        s => s,
        i => Rxn.pure(i.toString).flatTap(_ => ctr2.update(_ + 1)),
        s => s,
      ).run[F]
      // due to these concurrent transactions, the underlying ops has a chance of retrying => elimination
      bgFiber1 <- ((ctr1.update(_ + 1) *> ctr2.update(_ + 1)).run[F] *> F.cede).foreverM[Unit].start
      rr <- F.both(F.cede *> e.leftOp("42").run[F], F.cede *> e.rightOp(99).run[F])
      (leftRes, rightRes) = rr
      _ <- (leftRes match {
        case Left(underlyingLeftResult) =>
          assertEqualsF(underlyingLeftResult, 42) *> assertEqualsF(rightRes, Left("99"))
        case Right(eliminationLeftResult) =>
          assertEqualsF(eliminationLeftResult, 99) *> assertEqualsF(rightRes, Right("42"))
      }).guarantee(bgFiber1.cancel)
    } yield ()
    t.replicateA_(20000)
  }

  test("EliminationStack.tagged") {
    testTaggedEliminationStack(EliminationStack.tagged[Int], 50000)
  }

  test("EliminationStack.taggedFlaky") {
    testTaggedEliminationStack(EliminationStack.taggedFlaky[Int], 25000)
  }

  private def testTaggedEliminationStack(
    newStack: Rxn[EliminationStack.TaggedEliminationStack[Int]],
    repeat: Int,
  ): F[Unit] = {
    val t = for {
      s <- newStack.run[F]
      _ <- assertResultF(s.tryPop.run[F], Left(None))
      _ <- assertResultF(s.push(42).run[F], Left(()))
      _ <- assertResultF(s.tryPop.run[F], Left(Some(42)))
      _ <- concurrentPushPopTest(
        s.tryPop.map {
          case Left(underlying) =>
            underlying
          case Right(eliminated) =>
            // println("elimination!")
            eliminated
        },
        i => s.push(i).map(_.fold(x => x, x => x))
      )
    } yield ()
    t.replicateA_(repeat)
  }
}
