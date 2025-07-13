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

import scala.concurrent.duration._

import cats.effect.{ IO, IOApp }

import dev.tauri.choam.core.Rxn
import dev.tauri.choam.data.Map
import dev.tauri.choam.stm.{ Txn, TRef }
import dev.tauri.choam.ce.{ RxnAppMixin, TxnAppMixin }

object GraalNativeExample extends IOApp.Simple with RxnAppMixin with TxnAppMixin {

  private final def rxn(map: Map[String, Int], i: Int): Rxn[Int] =
    map.putIfAbsent("foo", i).map(_.getOrElse(0))

  private final def txn(ref: TRef[String], s: String): Txn[String] =
    ref.getAndUpdate(_ => s)

  private val tsk = for {
    _ <- IO.println("Starting...")
    map <- Map.hashMap[String, Int].run[IO]
    ref <- TRef("a").commit
    vs <- IO.both(
      IO.cede *> IO.both(
        IO.cede *> txn(ref, "v1").commit,
        IO.cede *> txn(ref, "v2").commit,
      ),
      IO.cede *> IO.both(
        IO.cede *> rxn(map, 1).run[IO],
        IO.cede *> rxn(map, 2).run[IO],
      ),
    )
    _ <- IO.println(s"Got: $vs")
    _ <- IO.println("Sleeping...")
    _ <- IO.sleep(5.seconds)
    _ <- IO.println("Done.")
  } yield ()

  final override val run: IO[Unit] = {
    tsk.guaranteeCase { oc =>
      IO.println(s"Outcome: $oc")
    }
  }
}
