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
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util.RandomState

@Fork(3)
class RefInitBench {

  @Benchmark
  def releaseInit(bh: Blackhole, r: RandomState): Unit = {
    val i = r.nextInt()
    bh.consume(new refs.RefP1[Int](i, 0L, 0L, 0L, 0L, null : String))
  }

  @Benchmark
  def volatileInit(bh: Blackhole, r: RandomState): Unit = {
    val i = r.nextInt()
    bh.consume(new refs.RefP1[Int](i, 0L, 0L, 0L, 0L))
  }
}
