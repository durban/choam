/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package refs

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(3)
class RefInitBench {

  // PADDED:

  /** Padded, no write to `value` */
  @Benchmark
  def nullInitPadded(bh: Blackhole): Unit = {
    bh.consume(new RefP1[String](0L, 0L, 0L, 0L))
  }

  /** Padded, write `null` to `value` in release mode */
  @Benchmark
  def releaseInitPadded(bh: Blackhole): Unit = {
    bh.consume(new RefP1[String](null : String, 0L, 0L, 0L, 0L, null : String))
  }

  /** Padded, write `null` to `value` in volatile mode */
  @Benchmark
  def volatileInitPadded(bh: Blackhole): Unit = {
    bh.consume(new RefP1[String](null : String, 0L, 0L, 0L, 0L))
  }

  // UNPADDED:

  /** Unpadded, no write to `value` */
  @Benchmark
  def nullInitUnpadded(bh: Blackhole): Unit = {
    bh.consume(new RefU1[String](0L, 0L, 0L, 0L))
  }

  /** Unpadded, write `null` to `value` in release mode */
  @Benchmark
  def releaseInitUnpadded(bh: Blackhole): Unit = {
    bh.consume(new RefU1[String](null : String, 0L, 0L, 0L, 0L, null : String))
  }

  /** Unpadded, write `null` to `value` in volatile mode */
  @Benchmark
  def volatileInitUnpadded(bh: Blackhole): Unit = {
    bh.consume(new RefU1[String](null : String, 0L, 0L, 0L, 0L))
  }
}
