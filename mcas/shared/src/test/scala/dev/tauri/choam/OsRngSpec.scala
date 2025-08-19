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

import java.util.Arrays

import cats.syntax.all._
import cats.effect.kernel.Resource
import cats.effect.IO
import cats.effect.instances.spawn.parallelForGenSpawn

import munit.CatsEffectSuite

import internal.mcas.OsRng

final class OsRngSpec extends CatsEffectSuite {

  test("OsRng#nextBytes") {
    val rng = OsRng.mkNew()
    try {
      assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(-1) }.isLeft)
      assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(-1000) }.isLeft)
      assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(Int.MinValue) }.isLeft)
      checkNextBytes(rng, size = 0)
      checkNextBytes(rng, size = 1)
      checkNextBytes(rng, size = 2)
      checkNextBytes(rng, size = 7)
      checkNextBytes(rng, size = 8)
      checkNextBytes(rng, size = 9)
      checkNextBytes(rng, size = 10)
      checkNextBytes(rng, size = 256)
      checkNextBytes(rng, size = 257)
      checkNextBytes(rng, size = 4096)
      checkNextBytes(rng, size = 4098)
      checkNextBytes(rng, size = 32768)
      checkNextBytes(rng, size = 32769)
      checkNextBytes(rng, size = 32770)
      checkNextBytes(rng, size = 32771)
      assert(rng.nextBytes(4).exists(_ != 0.toByte))
    } finally {
      rng.close()
    }
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

  private val osRngResource: Resource[IO, OsRng] = {
    Resource.make(IO.blocking { OsRng.mkNew() })(osRng => IO.blocking { osRng.close() })
  }

  test("Multi-threaded use") {
    osRngResource.use { rng =>
      val rngs = List.fill(4)(rng)
      useInParallel(rngs, n = 301) *> useInParallel(rngs, n = 6)
    }
  }

  test("Use different RNGs") {
    osRngResource.replicateA(4).use { rngs =>
      useInParallel(rngs, n = 301) *> useInParallel(rngs, n = 6)
    }
  }

  private def useInParallel(rngs: List[OsRng], n: Int): IO[Unit] = {
    rngs.parTraverse { rng =>
      val full0 = new Array[Byte](n)
      IO(new Array[Byte](n)).flatMap { buff1 =>
        IO(new Array[Byte](n)).flatMap { buff2 =>
          val once = IO { rng.nextBytes(buff1); rng.nextBytes(buff2) } >> IO {
            assert(!Arrays.equals(buff1, full0))
            assert(!Arrays.equals(buff2, full0))
            assert(!Arrays.equals(buff1, buff2))
          } >> IO { Arrays.fill(buff1, 0.toByte); Arrays.fill(buff2, 0.toByte) }
          (once >> IO.cede).replicateA_(4096)
        }
      }
    }.void
  }

  test("Race") {
    osRngResource.use { rng =>
      val t = IO(new Array[Byte](6)).flatMap { buff1 =>
        IO(new Array[Byte](6)).flatMap { buff2 =>
          IO.both(
            IO(rng.nextBytes(buff1)),
            IO(rng.nextBytes(buff2)),
          ) *> IO {
            assert(!Arrays.equals(buff1, buff2))
          }
        }
      }
      t.replicateA_(4096)
    }
  }
}
