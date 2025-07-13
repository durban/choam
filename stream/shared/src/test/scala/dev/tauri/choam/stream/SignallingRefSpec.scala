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

import scala.concurrent.duration._

import cats.effect.IO
import fs2.concurrent.SignallingRef

final class SignallingRefSpec_ThreadConfinedMcas_TickedIO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with SignallingRefSpec[IO]

trait SignallingRefSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  test("RxnSignallingRef") {
    val N = 1000
    def writer(ref: SignallingRef[F, Int], next: Int): F[Unit] = {
      if (next > N) {
        F.unit
      } else {
        val wait = if ((next % 10) == 0) F.sleep(0.1.seconds) else F.cede
        (ref.set(next) *> wait) >> writer(ref, next + 1)
      }
    }
    def checkListeners(ref: SignallingRef[F, Int], min: Int, max: Int): F[Unit] = {
      F.defer {
        val noOfListeners: F[Int] = ref.asInstanceOf[Fs2SignallingRefWrapper[F, Int]].pubSub.numberOfSubscriptions
        noOfListeners.flatMap(n => assertF(clue(n) <= clue(max)) *> assertF(clue(n) >= clue(min)))
      }
    }
    for {
      rr <- signallingRef[F, Int](initial = 0).run[F]
      (_, ref) = rr
      listener = ref
        .discrete
        .takeThrough(_ < N)
        .compile
        .toList
      f1 <- listener.start
      _ <- this.tickAll
      _ <- checkListeners(ref, min = 1, max = 1)
      f2 <- listener.start
      _ <- this.tickAll
      _ <- checkListeners(ref, min = 2, max = 2)
      f3 <- (ref.discrete.takeThrough(_ < N).evalTap { n =>
        // we check the number of listeners during the stream:
        if ((n % 10) == 0) {
          checkListeners(ref, min = 1, max = 3)
        } else {
          F.unit
        }
      }.compile.toList <* (
        // and we also check them after the stream:
        checkListeners(ref, min = 0, max = 2)
      )).start
      _ <- this.tickAll
      fw <- writer(ref, 1).start
      _ <- this.tickAll
      _ <- fw.joinWithNever
      l3 <- f3.joinWithNever // raises error if not cleaned up properly
      l1 <- f1.joinWithNever
      l2 <- f2.joinWithNever
      // we mustn't lose elements:
      _ <- assertEqualsF(l1, (1 to N).toList)
      _ <- assertEqualsF(l2, (1 to N).toList)
      _ <- assertEqualsF(l3, (1 to N).toList)
      _ <- checkListeners(ref, min = 0, max = 0)
    } yield ()
  }
}
