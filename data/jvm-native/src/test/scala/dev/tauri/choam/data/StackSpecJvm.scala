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

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import cats.effect.IO

import core.{ Rxn, Ref }

final class StackSpec_Treiber_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with StackSpecTreiberJvm[IO]

final class StackSpec_Treiber_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with StackSpecTreiberJvm[IO]

final class StackSpec_Elimination2_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with StackSpecElimination12Jvm[IO]
  with StackSpecElimination2[IO]

final class StackSpec_Elimination2_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with StackSpecElimination12Jvm[IO]
  with StackSpecElimination2[IO]

trait StackSpecTreiberJvm[F[_]]
  extends StackSpecTreiber[F]
  with StackSpecJvm[F] { this: McasImplSpec =>
}

trait StackSpecElimination12Jvm[F[_]]
  extends StackSpecJvm[F] { this: StackSpec[F] & McasImplSpec =>

  test("Elimination stack conflict before the elimination") {
    val randomSleep: F[Unit] = F.delay(ThreadLocalRandom.current().nextInt(10)).flatMap { x =>
      F.sleep(x.millis)
    }
    val once = for {
      ref <- Ref(0).run[F]
      stack <- this.newStack[String]()
      rxn1 = ref.update(_ + 1) *> stack.push("a")
      rxn2 = ref.update(_ + 2) *> stack.tryPop
      tsk = F.both(F.cede *> rxn1.run[F], F.cede *> rxn2.run[F])
      _ <- F.cede
      _ <- tsk.parReplicateA_(8)
    } yield ()
    (once *> randomSleep).replicateA_(768)
  }
}

trait StackSpecJvm[F[_]] { this: StackSpec[F] & McasImplSpec =>

  test("Multiple producers/consumers") {
    val N = 5
    val tsk = for {
      s <- this.newStack[String]()
      _ <- s.push("a").run[F]
      _ <- s.push("b").run[F]
      _ <- s.push("c").run[F]
      _ <- s.push("d").run[F]
      poppers <- (s.tryPeek * s.tryPop).run[F].parReplicateA(N).start
      pushers <- s.push("x").run[F].parReplicateA(N).start
      popRes <- poppers.joinWithNever
      _ <- pushers.joinWithNever
      remaining <- {
        def drain(acc: List[String]): F[List[String]] = {
          s.tryPop.run[F].flatMap {
            case Some(v) => drain(v :: acc)
            case None => F.pure(acc)
          }
        }
        drain(Nil)
      }
      _ <- popRes.traverse[F, Unit] {
        case (None, None) => F.unit // ok
        case (Some(peeked), Some(popped)) => assertEqualsF(peeked, popped)
        case (s @ Some(_), None) => failF(s"peeked = $s; popped = None")
        case (None, s @ Some(_)) => failF(s"peeked = None; popped = $s")
      }
      _ <- assertEqualsF(
        (popRes.collect { case (_, Some(v)) => v } ++ remaining).toSet,
        (List("a", "b", "c", "d") ++ List.fill(N)("x")).toSet,
      )
    } yield ()
    tsk.replicateA(512).void
  }

  test("Elimination stack conflict after the elimination") { // TODO: expected failure with EliminationStack
    // This test works fine with a Treiber stack, but
    // (non-deterministically) can fail with an elimination
    // stack. There are 2 possible ways for it to fail:
    // - with a "Couldn't merge logs" assertion failure:
    //   - here one of the logs (`rxn2`'s) is empty, so the
    //     reason for the failure is that the *other* log
    //     needs extending, which fails;
    // - with one of the `assertEqualsF`s below failing
    //   (see below for an explanation).
    val once = for {
      ref <- Ref(0).run[F]
      ref2 <- Ref(0).run[F] // invariant: always twice the value of `ref`
      stack <- this.newStack[String]()
      rxn1 = ref.getAndSet(42).flatMap { ov =>
        stack.push("a") *> ref2.getAndSet(84).map { ov2 => (ov, ov2) }
      }
      rxn2 = stack.tryPop.flatMap {
        case None => Rxn.unsafe.retry
        case Some(s) => ref2.getAndSet(66).flatMap { ov2 =>
          ref.getAndSet(33).map { ov => (ov, ov2, s) }
        }
      }
      misc1 = stack.push("b")
      misc2 = stack.tryPop
      tsk = F.both(
        F.both(F.cede *> rxn1.run[F], F.cede *> rxn2.run[F]),
        F.both(F.cede *> misc1.run[F], F.cede *> misc2.run[F]).parReplicateA_(6),
      ).map(_._1)
      _ <- F.cede
      r <- tsk
      rxn1Results = r._1
      rxn2Results = r._2
      // These can fail with an elimination stack; the reason is that for
      // not *entirely* independent reactions the current `Descriptor.merge` can
      // succeed, in which case they're committed together, but this can
      // cause non-linearizable results, like these:
      _ <- assertEqualsF(rxn1Results._2, rxn1Results._1 * 2, clue = (rxn1Results, rxn2Results).toString)
      _ <- assertEqualsF(rxn2Results._2, rxn2Results._1 * 2, clue = (rxn1Results, rxn2Results).toString)
      // when it fails: (rxn1Results, rxn2Results) == ((0,66),(42,0,a))
    } yield ()
    (once *> F.sleep(0.01.seconds)).replicateA_(512)
  }
}
