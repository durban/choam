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

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration._

import cats.effect.IO

import munit.{ CatsEffectSuite, Location }

class PromiseSpecNaiveKCAS
  extends PromiseSpec
  with SpecNaiveKCAS

class PromiseSpecEMCAS
  extends PromiseSpec
  with SpecEMCAS

abstract class PromiseSpec extends CatsEffectSuite { this: KCASImplSpec =>

  // TODO:
  def assertF(cond: Boolean, clue: String = "assertion failed")(implicit loc: Location): IO[Unit] =
    IO { this.assert(cond, clue) }

  // TODO:
  def assertEqualsF[A, B](obtained: A, expected: B, clue: String = "values are not the same")(implicit loc: Location, ev: B <:< A): IO[Unit] =
    IO { this.assertEquals[A, B](obtained, expected, clue) }

  test("Completing an empty promise should call all registered callbacks") {
    for {
      p <- Promise[Int].run[IO]
      act = p.get[IO]
      fib1 <- act.start
      fib2 <- act.start
      _ <- IO.sleep(0.1.seconds)
      b <- (React.pure(42) >>> p.tryComplete).run[IO]
      res1 <- fib1.join
      res2 <- fib2.join
      _ <- assertF(b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
    } yield ()
  }

  test("Completing a fulfilled promise should not be possible") {
    for {
      p <- Promise[Int].run[IO]
      _ <- assertIO(p.tryComplete[IO](42), true)
      _ <- assertIO(p.tryComplete[IO](42), false)
      _ <- assertIO(p.tryComplete[IO](99), false)
    } yield ()
  }

  test("Completing a fulfilled promise should not call any callbacks") {
    for {
      p <- Promise[Int].run[IO]
      _ <- assertIO(p.tryComplete[IO](42), true)
      cnt <- IO { new AtomicLong(0L) }
      act = p.get[IO].map { x =>
        cnt.incrementAndGet()
        x
      }
      res1 <- act
      res2 <- act
      b <- (React.pure(42) >>> p.tryComplete).run[IO]
      _ <- assertF(!b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
      _ <- assertIO(IO { cnt.get() }, 2L)
    } yield ()
  }
}
