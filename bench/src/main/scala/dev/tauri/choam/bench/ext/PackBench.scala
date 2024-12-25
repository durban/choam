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
package ext

import org.openjdk.jmh.annotations._

import util.RandomState

@Fork(value = 2) // , jvmArgsAppend = Array("-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintIntrinsics"))
@Threads(1)
@BenchmarkMode(Array(Mode.AverageTime))
class PackBench { // see HAMT

  @Benchmark
  def packSizeAndBlue(r: RandomState): Int = {
    Hamt_packSizeAndBlueInternal(r.nextInt(), r.nextBoolean())
  }

  private[this] final def Hamt_packSizeAndBlueInternal(size: Int, isBlue: Boolean): Int = {
    val x = (-1) * java.lang.Math.abs(java.lang.Boolean.compare(isBlue, true))
    size * ((x << 1) + 1)
  }

  @Benchmark
  def packSizeAndBlueExperimental(r: RandomState): Int = {
    packSizeAndBlueExperimental(r.nextInt(), r.nextBoolean())
  }

  private[this] final def packSizeAndBlueExperimental(size: Int, isBlue: Boolean): Int = {
    if (isBlue) size
    else -size
  }
}
