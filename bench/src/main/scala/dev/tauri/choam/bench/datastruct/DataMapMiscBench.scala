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
package datastruct

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

import DataMapBench.DummySt

@Fork(2)
@Threads(2)
class DataMapMiscBench {

  @Benchmark
  def hashKeyJava(s: DummySt, bh: Blackhole, k: McasImplState): Unit = {
    val keys = s.keys
    val key: AnyRef = keys(k.nextIntBounded(keys.length))
    bh.consume(key.hashCode())
  }

  @Benchmark
  def hashKeyScala(s: DummySt, bh: Blackhole, k: McasImplState): Unit = {
    val keys = s.keys
    val key: String = keys(k.nextIntBounded(keys.length))
    bh.consume(s.hash.hash(key))
  }

  @Benchmark
  def compareKeyJava(s: DummySt, bh: Blackhole, k: McasImplState): Unit = {
    val keys = s.keys
    val key1: AnyRef = keys(k.nextIntBounded(keys.length))
    val dummyKeys = s.dummyKeys
    val key2: AnyRef = dummyKeys(k.nextIntBounded(dummyKeys.length))
    bh.consume(key1.asInstanceOf[java.lang.Comparable[AnyRef]].compareTo(key2))
  }

  @Benchmark
  def compareKeyScala(s: DummySt, bh: Blackhole, k: McasImplState): Unit = {
    val keys = s.keys
    val key1: String = keys(k.nextIntBounded(keys.length))
    val dummyKeys = s.dummyKeys
    val key2: String = dummyKeys(k.nextIntBounded(dummyKeys.length))
    bh.consume(s.order.compare(key1, key2))
  }
}
