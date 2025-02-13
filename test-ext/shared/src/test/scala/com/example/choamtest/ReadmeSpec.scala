/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

package com.example.choamtest

import dev.tauri.choam.BaseSpec

final class ReadmeSpec extends BaseSpec {

  ////////////////////////////////
  import dev.tauri.choam.{ Ref, Rxn }

  def incrBoth(x: Ref[Int], y: Ref[Int]): Rxn[Any, Unit] = {
    x.update(_ + 1) *> y.update(_ + 1)
  }
  ////////////////////////////////
  import cats.effect.{ IO, IOApp }
  import dev.tauri.choam.ce.RxnAppMixin

  object MyMain extends IOApp.Simple with RxnAppMixin {
    override def run: IO[Unit] = for {
      // create two refs:
      x <- Ref(0).run[IO]
      y <- Ref(42).run[IO]
      // increment their values atomically:
      _ <- incrBoth(x, y).run[IO]
    } yield ()
  }
  ////////////////////////////////

  test("Example in README.md") {
    MyMain.run
  }
}
