/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

class AsyncQueueSpec_Prim_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncQueueSpec[IO]
  with AsyncQueueImplPrim[IO]

class AsyncQueueSpec_Derived_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncQueueImplDerived[IO]

trait AsyncQueueImplPrim[F[_]] extends AsyncQueueSpec[F] { this: KCASImplSpec =>
  protected final override def newQueue[G[_] : Reactive, A] =
    AsyncQueue.primitive[G, A].run[G]
}

trait AsyncQueueImplDerived[F[_]] extends AsyncQueueSpec[F] { this: KCASImplSpec =>
  protected final override def newQueue[G[_] : Reactive, A] =
    AsyncQueue.derived[G, A].run[G]
}

trait AsyncQueueSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  protected def newQueue[G[_] : Reactive, A]: G[AsyncQueue[G, A]]

  test("AsyncQueue non-empty deque") {
    for {
      s <- newQueue[F, String]
      _ <- s.enqueue[F]("a")
      _ <- s.enqueue[F]("b")
      _ <- s.enqueue[F]("c")
      _ <- assertResultF(s.deque, "a")
      _ <- assertResultF(s.deque, "b")
      _ <- assertResultF(s.deque, "c")
    } yield ()
  }

  test("AsyncQueue empty deque") {
    for {
      s <- newQueue[F, String]
      f1 <- s.deque.start
      _ <- F.sleep(0.1.seconds)
      f2 <- s.deque.start
      _ <- F.sleep(0.1.seconds)
      f3 <- s.deque.start
      _ <- F.sleep(0.1.seconds)
      _ <- s.enqueue[F]("a")
      _ <- s.enqueue[F]("b")
      _ <- s.enqueue[F]("c")
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("AsyncQueue#asCatsQueue") {
    for {
      q <- newQueue[F, String]
      cq <- q.asCatsQueue
      f <- cq.take.start
      _ <- F.sleep(0.1.seconds)
      _ <- q.enqueue[F]("a")
      _ <- assertResultF(f.joinWithNever, "a")
      _ <- assertResultF(cq.tryTake, None)
      f <- q.deque.start
      _ <- cq.offer("b")
      _ <- assertResultF(f.joinWithNever, "b")
      _ <- assertResultF(cq.tryOffer("c"), true)
      _ <- assertResultF(q.tryDeque.run[F], Some("c"))
      // TODO: cq.size not implemented yet
    } yield ()
  }
}
