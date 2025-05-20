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

package dev.tauri.choam
package internal
package random

import cats.effect.SyncIO

import java.security.SecureRandom

final class RandomSpecJs_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with RandomSpecJs[SyncIO]

trait RandomSpecJs[F[_]] extends RandomSpec[F] { this: McasImplSpec =>

  test("SecureRandom (JS)") {
    val bt = System.nanoTime()
    val s = new SecureRandom()
    s.nextBytes(new Array[Byte](20)) // force seed
    val at = System.nanoTime()
    println(s"Default SecureRandom: ${s.toString} (in ${at - bt}ns)")
  }
}
