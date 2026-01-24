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

package dev.tauri.choam
package internal
package random

import java.security.SecureRandom

import cats.effect.SyncIO

final class RandomSpecJvm_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with RandomSpecJvm[SyncIO]

final class RandomSpecJvm_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with RandomSpecJvm[SyncIO]

trait RandomSpecJvm[F[_]] extends RandomSpecJvmNat[F] { this: McasImplSpec =>

  test("SecureRandom (JVM)") {
    val bt = System.nanoTime()
    val s = new SecureRandom()
    s.nextBytes(new Array[Byte](20)) // force seed
    val at = System.nanoTime()
    println(s"Default SecureRandom: ${s.toString} (in ${at - bt}ns)")
  }
}
