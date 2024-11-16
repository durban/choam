/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.IO
import cats.effect.instances.spawn.parallelForGenSpawn

final class StackSpec_Treiber_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with StackSpecTreiberJvm[IO]

final class StackSpec_Treiber_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with StackSpecTreiberJvm[IO]

final class StackSpec_Elimination_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with StackSpecEliminationJvm[IO]

final class StackSpec_Elimination_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with StackSpecEliminationJvm[IO]

trait StackSpecTreiberJvm[F[_]]
  extends StackSpecTreiber[F]
  with StackSpecJvm[F] { this: McasImplSpec =>
}

trait StackSpecEliminationJvm[F[_]]
  extends StackSpecElimination[F]
  with StackSpecJvm[F] { this: McasImplSpec =>

  test("Elimination stack conflict before the elimination".fail) { // TODO: expected failure
    val once = for {
      ref <- Ref.unpadded(0).run[F]
      stack <- this.newStack[String]()
      rxn1 = ref.update(_ + 1) *> stack.push.provide("a")
      rxn2 = ref.update(_ + 2) *> stack.tryPop
      tsk = F.both(F.cede *> rxn1.run[F], F.cede *> rxn2.run[F])
      _ <- F.cede
      _ <- tsk.parReplicateA_(8)
    } yield ()
    (once *> F.sleep(0.01.seconds)).replicateA_(128)
  }
}

trait StackSpecJvm[F[_]] { this: StackSpec[F] with McasImplSpec =>

  test("Multiple producers/consumers") {
    val N = 4
    val tsk = for {
      s <- this.newStack[String]()
      _ <- s.push[F]("a")
      _ <- s.push[F]("b")
      _ <- s.push[F]("c")
      _ <- s.push[F]("d")
      poppers <- F.parReplicateAN(Int.MaxValue)(replicas = N, ma = s.tryPop.run[F]).start
      pushers <- F.parReplicateAN(Int.MaxValue)(replicas = N, ma = s.push[F]("x")).start
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
      _ <- assertEqualsF(
        (popRes.collect { case Some(v) => v } ++ remaining).toSet,
        (List("a", "b", "c", "d") ++ List.fill(N)("x")).toSet,
      )
    } yield ()
    tsk.replicateA(64).void
  }

  test("Elimination stack conflict after the elimination".ignore) { // TODO: expected failure with EliminationStack
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
      ref <- Ref.unpadded(0).run[F]
      ref2 <- Ref.unpadded(0).run[F] // invariant: always twice the value of `ref`
      stack <- this.newStack[String]()
      rxn1 = ref.getAndSet.provide(42).flatMap { ov =>
        stack.push.provide("a") *> ref2.getAndSet.provide(84).map { ov2 => (ov, ov2) }
      }
      rxn2 = stack.tryPop.flatMapF {
        case None => Rxn.unsafe.retry
        case Some(s) => ref2.getAndSet.provide(66).flatMap { ov2 =>
          ref.getAndSet.provide(33).map { ov => (ov, ov2, s) }
        }
      }
      misc1 = stack.push.provide("b")
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
