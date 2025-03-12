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

package com.example

import cats.effect.{ IO, IOApp }

import dev.tauri.choam.core.Axn
import dev.tauri.choam.refs.Ref
import dev.tauri.choam.ce.RxnAppMixin

object Main extends IOApp.Simple with RxnAppMixin {

  final def rxn(ref: Ref[String], s: String): Axn[String] =
    ref.getAndUpdate(_ => s)

  final override val run: IO[Unit] = for {
    _ <- IO.println("Starting...")
    ref <- Ref("a").run[IO]
    vs <- IO.both(
      IO.cede *> rxn(ref, "v1").run[IO],
      IO.cede *> rxn(ref, "v2").run[IO],
    )
    _ <- IO.println(s"Got: $vs")
    _ <- IO.println("Done.")
  } yield ()
}
