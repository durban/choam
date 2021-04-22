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

final class AsyncStackSpec_Impl1_NaiveKCAS_IO
  extends BaseSpecIO
  with SpecNaiveKCAS
  with AsyncStackSpec[IO]
  with AsyncStackImpl1[IO]

final class AsyncStackSpec_Impl1_NaiveKCAS_ZIO
  extends BaseSpecZIO
  with SpecNaiveKCAS
  with AsyncStackSpec[zio.Task]
  with AsyncStackImpl1[zio.Task]

final class AsyncStackSpec_Impl1_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncStackSpec[IO]
  with AsyncStackImpl1[IO]

final class AsyncStackSpec_Impl1_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with AsyncStackSpec[zio.Task]
  with AsyncStackImpl1[zio.Task]

// TODO: doesn't work with NaiveKCAS (RemoveQueue uses `null` as sentinel)
// class AsyncStackSpec_Impl2_NaiveKCAS_IO
//   extends BaseSpecIO
//   with SpecNaiveKCAS
//   with AsyncStackImpl2[IO]

final class AsyncStackSpec_Impl2_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncStackImpl2[IO]

final class AsyncStackSpec_Impl2_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with AsyncStackImpl2[zio.Task]

trait AsyncStackImpl1[F[_]] extends AsyncStackSpec[F] { this: KCASImplSpec =>
  protected final override def newStack[G[_] : Reactive, A]: G[AsyncStack[G, A]] =
    AsyncStack.impl1[G, A].run[G]
}

trait AsyncStackImpl2[F[_]] extends AsyncStackSpec[F] { this: KCASImplSpec =>
  protected final override def newStack[G[_] : Reactive, A]: G[AsyncStack[G, A]] =
    AsyncStack.impl2[G, A].run[G]
}

trait AsyncStackSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  protected def newStack[G[_] : Reactive, A]: G[AsyncStack[G, A]]

  test("pop on a non-empty stack should work like on Treiber stack") {
    for {
      s <- newStack[F, String]
      _ <- s.push[F]("foo")
      _ <- s.push[F]("bar")
      _ <- assertResultF(s.pop, "bar")
      _ <- assertResultF(s.pop, "foo")
    } yield ()
  }

  test("pop on a non-empty stack should work for concurrent pops") {
    for {
      s <- newStack[F, String]
      _ <- s.push[F]("xyz")
      _ <- s.push[F]("foo")
      _ <- s.push[F]("bar")
      pop = s.pop
      f1 <- pop.start
      f2 <- pop.start
      p1 <- f1.joinWithNever
      p2 <- f2.joinWithNever
      _ <- assertEqualsF(Set(p1, p2), Set("foo", "bar"))
      _ <- assertResultF(pop, "xyz")
    } yield ()
  }

  test("pop on an empty stack should complete with the correponding push") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      _ <- s.push[F]("foo")
      p1 <- f1.joinWithNever
      _ <- assertEqualsF(p1, "foo")
    } yield ()
  }

  test("pop on an empty stack should work with racing pushes") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      f2 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      _ <- s.push[F]("foo")
      _ <- assertResultF(f1.joinWithNever, "foo")
      _ <- s.push[F]("bar")
      _ <- assertResultF(f2.joinWithNever, "bar")
    } yield ()
  }

  test("pops should be served in a FIFO manner") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      f2 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      f3 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      _ <- s.push[F]("a")
      _ <- s.push[F]("b")
      _ <- s.push[F]("c")
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f2.joinWithNever, "b")
      _ <- assertResultF(f3.joinWithNever, "c")
    } yield ()
  }

  test("cancellation should not cause elements to be lost") {
    for {
      s <- newStack[F, String]
      f1 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      f2 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      f3 <- s.pop.start
      _ <- F.sleep(0.1.seconds)
      _ <- f2.cancel
      _ <- s.push[F]("a")
      _ <- s.push[F]("b")
      _ <- s.push[F]("c")
      _ <- assertResultF(f1.joinWithNever, "a")
      _ <- assertResultF(f3.joinWithNever, "b")
      _ <- assertResultF(s.pop, "c")
    } yield ()
  }
}
