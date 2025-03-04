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
package async

import scala.concurrent.duration._

import cats.effect.IO

final class WaitListSpec_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with WaitListSpec[IO]

trait WaitListSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  test("WaitList around a Ref") {
    for {
      ref <- Ref[Option[Int]](None).run[F]
      wl <- WaitList[Int](
        ref.get,
        ref.getAndSet.contramap[Int](Some(_)).void
      ).run[F]
      f1 <- wl.asyncGet.start
      _ <- this.tickAll
      f2 <- wl.asyncGet.start
      _ <- wl.set0[F](42)
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- wl.set0[F](21)
      _ <- assertResultF(f2.joinWithNever, 21)
    } yield ()
  }

  test("AsyncQueue.synchronous") {
    object Cancelled extends Exception
    for {
      q <- AsyncQueue.synchronous[Int].run[F]
      _ <- assertResultF(q.tryEnqueue[F](1), false)
      _ <- assertResultF(q.tryDeque.run[F], None)
      f1 <- q.enqueue(2).attempt.start
      _ <- this.tickAll
      _ <- F.sleep(1.second)
      _ <- f1.cancel
      _ <- assertResultF(f1.joinWith(onCancel = F.pure(Left(Cancelled))), Left(Cancelled))
      f2 <- q.deque.attempt.start
      _ <- this.tickAll
      _ <- F.sleep(1.second)
      _ <- f2.cancel
      _ <- assertResultF(f2.joinWith(onCancel = F.pure(Left(Cancelled))), Left(Cancelled))
      f3 <- q.enqueue(3).start
      _ <- this.tickAll
      _ <- assertResultF(q.tryDeque.run[F], Some(3))
      _ <- f3.joinWithNever
      f4 <- q.deque.start
      _ <- this.tickAll
      f5 <- q.deque.start
      _ <- this.tickAll
      _ <- q.enqueue(4)
      _ <- assertResultF(f4.joinWithNever, 4)
      _ <- q.enqueue(5)
      _ <- assertResultF(f5.joinWithNever, 5)
      _ <- assertResultF(q.tryEnqueue[F](42), false)
      _ <- assertResultF(q.tryDeque.run[F], None)
    } yield ()
  }

  test("AsyncQueue.synchronous both empty and full") {
    for {
      q <- AsyncQueue.synchronous[Int].run[F]
      // a setter is waiting:
      f1 <- q.enqueue(1).start
      _ <- this.tickAll
      // tryGet must complete it:
      _ <- assertResultF(q.tryDeque.run[F], Some(1))
      _ <- assertResultF(f1.joinWithNever, ())
      // another setter is waiting:
      f2 <- q.enqueue(2).start
      _ <- this.tickAll
      // asyncGet must complete it:
      _ <- assertResultF(q.deque, 2)
      _ <- assertResultF(f2.joinWithNever, ())
      // a getter is waiting:
      f3 <- q.deque.start
      _ <- this.tickAll
      // trySet must complete it:
      _ <- assertResultF(q.tryEnqueue.apply[F](3), true)
      _ <- assertResultF(f3.joinWithNever, 3)
      // another getter is waiting:
      f4 <- q.deque.start
      _ <- this.tickAll
      // asyncSet must complete it:
      _ <- assertResultF(q.enqueue(4), ())
      _ <- assertResultF(f4.joinWithNever, 4)
    } yield ()
  }
}
