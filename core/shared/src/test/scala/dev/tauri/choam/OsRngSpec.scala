/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.Arrays

import cats.effect.IO
import cats.effect.instances.spawn.parallelForGenSpawn

import random.OsRng

final class OsRngSpecIO
  extends BaseSpecIO
  with OsRngSpec[IO]
  with SpecNoMcas

trait OsRngSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("OsRng#nextBytes") {
    val rng = OsRng.mkNew()
    assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(-1) }.isLeft)
    assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(-1000) }.isLeft)
    assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(Int.MinValue) }.isLeft)
    checkNextBytes(rng, size = 0)
    checkNextBytes(rng, size = 1)
    checkNextBytes(rng, size = 256)
    checkNextBytes(rng, size = 257)
    checkNextBytes(rng, size = 4096)
    checkNextBytes(rng, size = 32768)
    assert(rng.nextBytes(4).exists(_ != 0.toByte))
  }

  private def checkNextBytes(rng: OsRng, size: Int): Unit = {
    val a = rng.nextBytes(size)
    assertEquals(a.length, size)
    val b = rng.nextBytes(size)
    assertEquals(b.length, size)
    if (size > 2) {
      // at most 1/16777216 chance of accidental failure
      assert(!Arrays.equals(a, b))
    }
  }

  final val N = 256

  val full0 = new Array[Byte](N)

  test("Multi-threaded use") {
    val rng = OsRng.mkNew()
    useInParallel(List.fill(4)(rng))
  }

  test("Use different RNGs") {
    useInParallel(List.fill(4) { OsRng.mkNew() })
  }

  private def useInParallel(rngs: List[OsRng]): F[Unit] = {
    rngs.parTraverse { rng =>
      F.delay(new Array[Byte](N)).flatMap { buff =>
        val once = F.delay(rng.nextBytes(buff)) >> F.delay(assert(!Arrays.equals(buff, full0))) >> F.delay(Arrays.fill(buff, 0.toByte))
        (once >> F.cede).replicateA_(4096)
      }
    }.void
  }
}
