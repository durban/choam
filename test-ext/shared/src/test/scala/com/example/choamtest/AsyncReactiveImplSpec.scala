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

import dev.tauri.choam.core.Ref
import dev.tauri.choam.async.Promise

final class AsyncReactiveImplSpec extends BaseSpecMyIO {

  test("Ref") {
    val rxn = Ref[String]("foo").flatMapF { ref =>
      ref.updateAndGet(_ + "bar")
    }
    assertResultF(rxn.run[MyIO], "foobar")
  }

  test("Promise") {
    for {
      p <- Promise[String].run[MyIO]
      fib <- p.get.start
      _ <- assertResultF(p.complete0.run[MyIO]("foo"), true)
      _ <- assertResultF(fib.joinWithNever, "foo")
    } yield ()
  }
}
