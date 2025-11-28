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

import cats.syntax.all._
import cats.effect.{ IO, IOApp, ExitCode }

import dev.tauri.choam.core.Ref
import dev.tauri.choam.stm.TRef
import dev.tauri.choam.ce.{ RxnAppMixin, TxnAppMixin }

object CeAppMixinTest extends IOApp with RxnAppMixin with TxnAppMixin {

  final override def run(args: List[String]): IO[ExitCode] = for {
    ref <- Ref(0).run
    tref <- TRef(0).commit
    _ <- IO.both(ref.update(_ + 1).run, tref.update(_ + 2).commit)
    vv <- (ref.get.run, tref.get.commit).tupled
    _ <- IO {
      assert(vv._1 == 1)
      assert(vv._2 == 2)
    }
    _ <- IO.println("OK")
  } yield ExitCode.Success
}
