/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
import cats.effect.std.Random

final class NullMcasRxnSpecIO
  extends BaseSpecIO
  with NullMcasRxnSpec[IO]
  with SpecNullMcas

trait NullMcasRxnSpec[F[_]] extends BaseSpecAsyncF[F] { this: SpecNullMcas =>

  test("NullMcas must be able to run a create-only Rxn") {
    for {
      _ <- assertF(clue(this.mcasImpl.getClass().getName()).endsWith("NullMcas$"))
      rnd <- Rxn.fastRandom.run[F]
      _ = (rnd: Random[Axn])
      ref <- Ref("abc").run[F]
      _ = (ref: Ref[String])
      refArr <- Ref.array(42, "init").run[F]
      _ = (refArr: Ref.Array[String])
      _ = (refArr.unsafeGet(0): Ref[String])
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      _ = (ex: Exchanger[String, Int])
    } yield ()
  }
}
