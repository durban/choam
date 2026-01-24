/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.IO

import dev.tauri.choam.ChoamRuntime
import dev.tauri.choam.core.{ Reactive, Ref }
import dev.tauri.choam.internal.mcas.Mcas

final class McasImplOverrideSpec extends munit.CatsEffectSuite {

  test("Overriding MCAS impl with a system property should work on the JVM") {
    ChoamRuntime.make[IO].use { rt =>
      for {
        // check that the override is enabled:
        _ <- assertIO(IO { System.getProperty("dev.tauri.choam.internal.mcas.impl") }, "SpinLockMcas")
        // check that it indeed takes effect:
        _ <- IO {
          import scala.language.reflectiveCalls
          val mcasImpl = rt.asInstanceOf[{ def mcasImpl: Mcas }].mcasImpl
          val name = mcasImpl.getClass().getName()
          assert(clue(name).endsWith("SpinLockMcas"))
        }
        // check that it works:
        _ <- Reactive.from[IO](rt).use { implicit re =>
          for {
            ref <- Ref(42).run[IO]
            _ <- ref.update(_ + 1).run
            _ <- assertIO(ref.get.run, 43)
          } yield ()
        }
      } yield ()
    }
  }
}
