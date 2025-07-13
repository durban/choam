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

  test("SignallingRef#continuous") {
    def checkContinuous(lst: List[Int]): F[Unit] = F.delay {
      // it must have (in order):
      // 0 or more `0`
      // 0 or more `1`
      // 0 or more `2`
      // 0 or more `3`
      // (and nothing else)
      val zeros = lst.takeWhile(_ == 0).size
      val rest1 = lst.dropWhile(_ == 0)
      assert(!rest1.contains(0))
      val ones = rest1.takeWhile(_ == 1).size
      val rest2 = rest1.dropWhile(_ == 1)
      assert(!rest2.contains(1))
      val twos = rest2.takeWhile(_ == 2).size
      val rest3 = rest2.dropWhile(_ == 2)
      assert(!rest3.contains(2))
      val threes = rest3.takeWhile(_ == 3).size
      assertEquals(rest3.dropWhile(_ == 3), Nil)
      // System.err.println(s"Counted: $zeros '0's; $ones '1's; $twos '2's; $threes '3's")
      assert((zeros + ones + twos + threes) > 0)
    }
    def tickALot: F[Unit] = {
      val aLot = 1000
      assertResultF(this.tickN(aLot), aLot)
    }
    for {
      rr <- signallingRef[F, Int](initial = 0).run[F]
      (_, ref) = rr
      _ <- assertResultF(ref.continuous.take(3).compile.toList, List(0, 0, 0))
      fd <- ref.discrete.take(3).compile.toList.start
      _ <- this.tickAll
      intrpt <- F.deferred[Either[Throwable, Unit]]
      fc <- ref.continuous.interruptWhen(intrpt).compile.toList.start
      _ <- tickALot
      _ <- ref.set(1)
      _ <- tickALot
      _ <- assertResultF(ref.updateAndGet(_ + 1), 2)
      _ <- tickALot
      _ <- assertResultF(ref.modify(ov => (ov + 1, 42)), 42)
      _ <- tickALot
      _ <- assertResultF(fd.joinWithNever, List(1, 2, 3))
      _ <- tickALot
      _ <- intrpt.complete(Right(()))
      lst <- fc.joinWithNever
      _ <- checkContinuous(lst)
    } yield ()
  }

  test("SignallingRef#discrete") {
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
