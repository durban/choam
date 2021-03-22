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

final class ExchangerSpecEMCAS
  extends BaseSpecIO
  with SpecEMCAS
  with ExchangerSpec[IO]

trait ExchangerSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  test("Exchanger".ignore) {
    for {
      ex <- React.unsafe.exchanger[String, Int].run[F]
      r1 = ex.exchange
      r2 = ex.dual.exchange
      f1 <- r1[F]("foo").flatTap { _ => F.delay(println("f1 done")) }.start
      f2 <- r2[F](42).flatTap { _ => F.delay(println("f1 done")) }.start
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- assertResultF(f2.joinWithNever, "foo")
    } yield ()
  }
}
