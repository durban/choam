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
package stream

import cats.effect.IO
import fs2.Stream

import core.AsyncReactiveSpec

final class SignallingRefSpec_ThreadConfinedMcas_TickedIO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with SignallingRefSpec[IO]

trait SignallingRefSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec & TestContextSpec[F] =>

  test("RxnSignallingRef".ignore) {
    val N = 1000
    def writer(ref: RxnSignallingRef[F, Int], next: Int): F[Unit] = {
      if (next > N) {
        F.unit
      } else {
        (ref.set(next) *> F.cede) >> writer(ref, next + 1)
      }
    }
    def checkList(l: List[Int], max: Int): F[Unit] = {
      def go(l: List[Int], prev: Int): F[Unit] = l match {
        case Nil =>
          F.unit
        case h :: t =>
          // list should be increasing:
          (assertF(clue(prev) < clue(h)) *> assertF(h <= max)) >> (
            go(t, prev = h)
          )
      }
      // we assume that at least *some* updates are not lost:
      assertF(clue(l.length).toDouble >= (max.toDouble / 200.0)) *> go(l, -1)
    }
    def checkListeners(ref: RxnSignallingRef[F, Int], min: Int, max: Int): F[Unit] = {
      F.defer {
        val listeners = ref.asInstanceOf[Fs2SignallingRefWrapper[F, Int]].listeners.values.run[F]
        listeners.flatMap(n => assertF(clue(n.size) <= clue(max)) *> assertF(clue(n.size) >= clue(min)))
      }
    }
    for {
      ref <- signallingRef[F, Int](initial = 0).run[F]
      listener = ref
        .discrete
        .evalTap(_ => F.cede)
        .takeThrough(_ < N)
        .compile
        .toList
      f1 <- listener.start
      _ <- this.tickAll
      f2 <- listener.start
      _ <- this.tickAll
      f3 <- (ref.discrete.take(10).evalTap { _ =>
        // we check the number of listeners during and after the stream
        checkListeners(ref, min = 1, max = 3)
      } ++ Stream.exec(checkListeners(ref, min = 0, max = 2))).compile.toList.start
      _ <- this.tickAll
      fw <- writer(ref, 1).start
      _ <- this.tickAll
      _ <- fw.joinWithNever
      _ <- f3.joinWithNever // raises error if not cleaned up properly
      l1 <- f1.joinWithNever
      _ <- checkList(l1, max = N)
      l2 <- f2.joinWithNever
      _ <- checkList(l2, max = N)
      _ <- checkListeners(ref, min = 0, max = 0)
    } yield ()
  }
}
