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

import cats.effect.IO

final class RxnNewSpec_NaiveKCAS_IO
  extends BaseSpecIO
  with SpecNaiveKCAS
  with RxnNewSpec[IO]

final class RxnNewSpec_NaiveKCAS_ZIO
  extends BaseSpecZIO
  with SpecNaiveKCAS
  with RxnNewSpec[zio.Task]

final class RxnNewSpec_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnNewSpec[IO]

final class RxnNewSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with RxnNewSpec[zio.Task]

trait RxnNewSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  test("Creating and running deeply nested Rxn's should both be stack-safe") {
    def nest(
      n: Int,
      combine: (RxnNew[Int, Int], RxnNew[Int, Int]) => RxnNew[Int, Int]
    ): RxnNew[Int, Int] = {
      (1 to n).map(_ => RxnNew.lift[Int, Int](_ + 1)).reduce(combine)
    }
    val N = 1024 * 1024
    val r1: RxnNew[Int, Int] = nest(N, _ >>> _)
    val r2: RxnNew[Int, Int] = nest(N, (x, y) => (x * y).map(_._1 + 1))
    // TODO: val r3: Rxn[Int, Int] = nest(N, (x, y) => x.flatMap { _ => y })
    // TODO: val r4: Rxn[Int, Int] = nest(N, _ >> _)
    val r5: RxnNew[Int, Int] = nest(N, _ + _)
    // TODO: val r6: RxnNew[Int, Int] = nest(N, (x, y) => RxnNew.unsafe.delayComputed(x.map(Rxn.ret(_) >>> y)))
    assertEquals(r1.unsafePerform(42, this.kcasImpl), 42 + N)
    assertEquals(r2.unsafePerform(42, this.kcasImpl), 42 + N)
    // r3.##//unsafePerform(42, this.kcasImpl)
    // r4.##//unsafePerform(42, this.kcasImpl)
    assertEquals(r5.unsafePerform(42, this.kcasImpl), 42 + 1)
    // r6.## // TODO: r6.unsafePerform(42, this.kcasImpl)
  }

  test("Choice after >>>") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      y <- Ref("y").run[F]
      p <- Ref("p").run[F]
      q <- Ref("q").run[F]
      rea = (
        (
          (RxnNew.unsafe.cas(a, "a", "aa") + (RxnNew.unsafe.cas(b, "b", "bb") >>> RxnNew.unsafe.delay { _ =>
            this.kcasImpl.doSingleCas(y, "y", "-", this.kcasImpl.currentContext())
          })) >>> RxnNew.unsafe.cas(y, "-", "yy")
        ) +
        (RxnNew.unsafe.cas(p, "p", "pp") >>> RxnNew.unsafe.cas(q, "q", "qq"))
      )
      _ <- assertResultF(F.delay { rea.unsafePerform((), this.kcasImpl) }, ())
      _ <- assertResultF(a.unsafeInvisibleRead.run, "a")
      _ <- assertResultF(b.unsafeInvisibleRead.run, "bb")
      _ <- assertResultF(y.unsafeInvisibleRead.run, "yy")
      _ <- assertResultF(p.unsafeInvisibleRead.run, "p")
      _ <- assertResultF(q.unsafeInvisibleRead.run, "q")
    } yield ()
  }
}
