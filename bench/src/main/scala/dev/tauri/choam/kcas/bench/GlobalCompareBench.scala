/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas
package bench

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(6)
class GlobalCompareBench {

  @Benchmark
  def baseline(bh: Blackhole): Unit = {
    val r1 = Ref.mk("a")
    val r2 = Ref.mk("a")
    bh.consume(r1)
    bh.consume(r2)
  }

  @Benchmark
  def bench0(bh: Blackhole): Unit = {
    val r1 = Ref.mk("a")
    val r2 = Ref.mk("a")
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(Ref.globalCompare(r1, r2))
  }

  @Benchmark
  def bench1(bh: Blackhole): Unit = {
    val tlr = ThreadLocalRandom.current()
    val i0 = tlr.nextLong()
    bh.consume(tlr.nextLong())
    val r1 = Ref.mkWithId("a")(i0, tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
    val r2 = Ref.mkWithId("a")(i0, tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(Ref.globalCompare(r1, r2))
  }

  @Benchmark
  def bench2(bh: Blackhole): Unit = {
    val tlr = ThreadLocalRandom.current()
    val i0 = tlr.nextLong()
    bh.consume(tlr.nextLong())
    val i1 = tlr.nextLong()
    bh.consume(tlr.nextLong())
    val r1 = Ref.mkWithId("a")(i0, i1, tlr.nextLong(), tlr.nextLong())
    val r2 = Ref.mkWithId("a")(i0, i1, tlr.nextLong(), tlr.nextLong())
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(Ref.globalCompare(r1, r2))
  }

  @Benchmark
  def bench3(bh: Blackhole): Unit = {
    val tlr = ThreadLocalRandom.current()
    val i0 = tlr.nextLong()
    bh.consume(tlr.nextLong())
    val i1 = tlr.nextLong()
    bh.consume(tlr.nextLong())
    val i2 = tlr.nextLong()
    bh.consume(tlr.nextLong())
    val r1 = Ref.mkWithId("a")(i0, i1, i2, tlr.nextLong())
    val r2 = Ref.mkWithId("a")(i0, i1, i2, tlr.nextLong())
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(Ref.globalCompare(r1, r2))
  }
}
