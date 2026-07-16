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

import dev.tauri.choam.{ BaseSpecAsyncF, SpecDefaultMcas, BaseSpecIO }
import dev.tauri.choam.data.Stack
import dev.tauri.choam.async.AsyncStack

final class UnsealedSpec extends BaseSpecIO with BaseSpecAsyncF[IO] with SpecDefaultMcas {

  test("AsyncStack <: Stack (seemingly)") {
    AsyncStack[String].run[IO].map { asyncStack =>
      asyncStack : Stack[String]
    }
  }

  test("AsyncStack invariant functor") {
    AsyncStack[String].run[IO].map { asyncStack =>
      asyncStack.imap[String](s => s)(s => s)
    }
  }

  test("AsyncStack implicit evidence") {
    implicitly[AsyncStack[String] <:< Stack[String]]
  }
}
