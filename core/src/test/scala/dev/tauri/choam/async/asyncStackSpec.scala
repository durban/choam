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

class AsyncStackSpec_NaiveKCAS_IO
  extends BaseSpecIO
  with SpecNaiveKCAS
  with AsyncStackSpec[IO]

class AsyncStackSpec_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with AsyncStackSpec[IO]

trait AsyncStackSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  test("pop on a non-empty stack should work like on Treiber stack") {
    for {
      s <- AsyncStack[String].run[F]
      _ <- s.push[F]("foo")
      _ <- s.push[F]("bar")
      _ <- assertResultF(s.pop[F], "bar")
      _ <- assertResultF(s.pop[F], "foo")
    } yield ()
  }

  test("pop on a non-empty stack should work for concurrent pops") {
    for {
      s <- AsyncStack[String].run[F]
      _ <- s.push[F]("xyz")
      _ <- s.push[F]("foo")
      _ <- s.push[F]("bar")
      pop = s.pop[F]
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
      s <- AsyncStack[String].run[F]
      f1 <- s.pop[F].start
      _ <- F.sleep(0.1.seconds)
      _ <- s.push[F]("foo")
      p1 <- f1.joinWithNever
      _ <- assertEqualsF(p1, "foo")
    } yield ()
  }

  test("pop on an empty stack should work with racing pushes") {
    for {
      s <- AsyncStack[String].run[F]
      f1 <- s.pop[F].start
      _ <- F.sleep(0.1.seconds)
      f2 <- s.pop[F].start
      _ <- F.sleep(0.1.seconds)
      _ <- s.push[F]("foo")
      _ <- assertResultF(f1.joinWithNever, "foo")
      _ <- s.push[F]("bar")
      _ <- assertResultF(f2.joinWithNever, "bar")
    } yield ()
  }

  // TODO: it should "serve pops in a FIFO manner"
  // TODO: it should "be cancellable"
}
