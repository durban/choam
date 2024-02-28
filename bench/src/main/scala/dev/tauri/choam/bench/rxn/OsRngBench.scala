/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package rxn

import java.security.{ SecureRandom => JSecureRandom }

import org.openjdk.jmh.annotations._

import random.OsRng

import OsRngBench._

@Fork(3)
@Threads(2)
@BenchmarkMode(Array(Mode.Throughput))
class OsRngBench {

  @Benchmark
  def small_baseline(s: BaselineSharedSt, b: BufferSt): Unit = {
    s.javaSecureRandom.nextBytes(b.smallBuffer)
  }

  @Benchmark
  def small_osRandom(s: SharedSt, b: BufferSt): Unit = {
    s.osRandom.nextBytes(b.smallBuffer)
  }

  @Benchmark
  def medium_baseline(s: BaselineSharedSt, b: BufferSt): Unit = {
    s.javaSecureRandom.nextBytes(b.mediumBuffer)
  }

  @Benchmark
  def medium_osRandom(s: SharedSt, b: BufferSt): Unit = {
    s.osRandom.nextBytes(b.mediumBuffer)
  }

  @Benchmark
  def large_baseline(s: BaselineSharedSt, b: BufferSt): Unit = {
    s.javaSecureRandom.nextBytes(b.largeBuffer)
  }

  @Benchmark
  def large_osRandom(s: SharedSt, b: BufferSt): Unit = {
    s.osRandom.nextBytes(b.largeBuffer)
  }
}

object OsRngBench {

  @State(Scope.Benchmark)
  class SharedSt {
    val osRandom: OsRng =
      OsRng.mkNew()
  }

  @State(Scope.Benchmark)
  class BaselineSharedSt {
    val javaSecureRandom: JSecureRandom =
      new JSecureRandom
  }

  @State(Scope.Thread)
  class BufferSt {
    val smallBuffer: Array[Byte] =
      new Array[Byte](4)
    val mediumBuffer: Array[Byte] =
      new Array[Byte](32)
    val largeBuffer: Array[Byte] =
      new Array[Byte](512)
  }
}
