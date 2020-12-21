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

class PromiseSpec_NaiveKCAS_IO
  extends IOSpecMUnit
  with SpecNaiveKCAS
  with PromiseSpec[IO]

class PromiseSpec_EMCAS_IO
  extends IOSpecMUnit
  with SpecEMCAS
  with PromiseSpec[IO]

trait PromiseSpec[F[_]] extends BaseSpecF[F] { this: KCASImplSpec =>

  test("Completing an empty promise should call all registered callbacks") {
    for {
      p <- Promise[Int].run[F]
      act = p.get[F]
      fib1 <- act.start
      fib2 <- act.start
      _ <- tmF.sleep(0.1.seconds)
      b <- (React.pure(42) >>> p.tryComplete).run[F]
      res1 <- fib1.join
      res2 <- fib2.join
      _ <- assertF(b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
    } yield ()
  }

  test("Completing a fulfilled promise should not be possible") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.tryComplete[F](42), true)
      _ <- assertResultF(p.tryComplete[F](42), false)
      _ <- assertResultF(p.tryComplete[F](99), false)
    } yield ()
  }

  test("Completing a fulfilled promise should not call any callbacks") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.tryComplete[F](42), true)
      cnt <- F.delay { new AtomicLong(0L) }
      act = p.get[F].map { x =>
        cnt.incrementAndGet()
        x
      }
      res1 <- act
      res2 <- act
      b <- (React.pure(42) >>> p.tryComplete).run[F]
      _ <- assertF(!b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
      _ <- assertResultF(F.delay { cnt.get() }, 2L)
    } yield ()
  }
}
