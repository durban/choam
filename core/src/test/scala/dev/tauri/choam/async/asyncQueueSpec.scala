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

final class AsyncQueueSpec_Prim_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncQueueSpec[IO]
  with AsyncQueueImplPrim[IO]

final class AsyncQueueSpec_Prim_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with AsyncQueueSpec[zio.Task]
  with AsyncQueueImplPrim[zio.Task]

final class AsyncQueueSpec_Derived_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncQueueImplDerived[IO]

final class AsyncQueueSpec_Derived_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with AsyncQueueImplDerived[zio.Task]

final class AsyncQueueSpec_WithSize_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncQueueImplWithSize[IO]

final class AsyncQueueSpec_WithSize_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with AsyncQueueImplWithSize[zio.Task]

trait AsyncQueueImplPrim[F[_]] extends AsyncQueueSpec[F] { this: KCASImplSpec =>
  final override type Q[G[_], A] = AsyncQueue[G, A]
  protected final override def newQueue[G[_] : Reactive, A] =
    AsyncQueue.primitive[G, A].run[G]
}

trait AsyncQueueImplDerived[F[_]] extends AsyncQueueSpec[F] { this: KCASImplSpec =>
  final override type Q[G[_], A] = AsyncQueue[G, A]
  protected final override def newQueue[G[_] : Reactive, A] =
    AsyncQueue.derived[G, A].run[G]
}

trait AsyncQueueImplWithSize[F[_]] extends AsyncQueueSpec[F] { this: KCASImplSpec =>

  final override type Q[G[_], A] = AsyncQueue.WithSize[G, A]

  protected final override def newQueue[G[_] : Reactive, A] =
    AsyncQueue.withSize[G, A].run[G]

  test("AsyncQueue#toCats") {
    for {
      q <- newQueue[F, String]
      cq <- q.toCats
      _ <- assertResultF(cq.size, 0)
      f <- cq.take.start
      _ <- F.sleep(0.1.seconds)
      _ <- q.enqueue[F]("a")
      _ <- assertResultF(f.joinWithNever, "a")
      _ <- assertResultF(cq.size, 0)
      _ <- assertResultF(cq.tryTake, None)
      f <- q.deque.start
      _ <- cq.offer("b")
      _ <- assertResultF(f.joinWithNever, "b")
      _ <- assertResultF(cq.size, 0)
      _ <- assertResultF(cq.tryOffer("c"), true)
      _ <- assertResultF(cq.size, 1)
      _ <- assertResultF(q.tryDeque.run[F], Some("c"))
      _ <- assertResultF(cq.size, 0)
    } yield ()
  }

  test("dequeResource") {
    newQueue[F, String].flatMap { q =>
      q.dequeResource.use { g1 =>
        q.dequeResource.use { g2 =>
          q.dequeResource.use { g3 =>
            for {
              _ <- q.enqueue[F]("a")
              _ <- q.enqueue[F]("b")
              _ <- q.enqueue[F]("c")
              _ <- assertResultF(g1, "a")
              _ <- assertResultF(g2, "b")
              _ <- assertResultF(g3, "c")
            } yield ()
          }
        }
      }
    }
  }
}

trait AsyncQueueSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  type Q[G[_], A] <: AsyncQueue[G, A]

  protected def newQueue[G[_] : Reactive, A]: G[Q[G, A]]

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
}
