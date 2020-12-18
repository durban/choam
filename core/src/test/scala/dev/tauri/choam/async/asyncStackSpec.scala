/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

class AsyncStackSpecNaiveKCAS
  extends AsyncStackSpec
  with SpecNaiveKCAS

class AsyncStackSpecEMCAS
  extends AsyncStackSpec
  with SpecEMCAS

abstract class AsyncStackSpec extends BaseSpecMUnit { this: KCASImplSpec =>

  test("pop on a non-empty stack should work like on Treiber stack") {
    for {
      s <- AsyncStack[String].run[IO]
      _ <- s.push[IO]("foo")
      _ <- s.push[IO]("bar")
      _ <- assertResultF(s.pop[IO], "bar")
      _ <- assertResultF(s.pop[IO], "foo")
    } yield ()
  }

  test("pop on a non-empty stack should work for concurrent pops") {
    for {
      s <- AsyncStack[String].run[IO]
      _ <- s.push[IO]("xyz")
      _ <- s.push[IO]("foo")
      _ <- s.push[IO]("bar")
      pop = s.pop[IO]
      f1 <- pop.start
      f2 <- pop.start
      p1 <- f1.join
      p2 <- f2.join
      _ <- assertEqualsF(Set(p1, p2), Set("foo", "bar"))
      _ <- assertResultF(pop, "xyz")
    } yield ()
  }

  test("pop on an empty stack should complete with the correponding push") {
    for {
      s <- AsyncStack[String].run[IO]
      f1 <- s.pop[IO].start
      _ <- IO.sleep(0.1.seconds)
      _ <- s.push[IO]("foo")
      p1 <- f1.join
      _ <- assertEqualsF(p1, "foo")
    } yield ()
  }

  test("pop on an empty stack should work with racing pushes") {
    for {
      s <- AsyncStack[String].run[IO]
      f1 <- s.pop[IO].start
      _ <- IO.sleep(0.1.seconds)
      f2 <- s.pop[IO].start
      _ <- IO.sleep(0.1.seconds)
      _ <- s.push[IO]("foo")
      _ <- assertResultF(f1.join, "foo")
      _ <- s.push[IO]("bar")
      _ <- assertResultF(f2.join, "bar")
    } yield ()
  }

  // TODO: it should "serve pops in a FIFO manner"
  // TODO: it should "be cancellable"
}
