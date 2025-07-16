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
package bench
package ext

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util.McasImplStateBase
import core.{ Rxn, Ref, RetryStrategy }
import SpinBench.SpinSt

@Fork(3)
class SpinBench {

  @Benchmark
  @Group("withOnSpinWait1")
  def withOnSpinWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithOnSpinWait1))
  }

  @Benchmark
  @Group("withExpWait1")
  def withExpWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithExpWait1))
  }

  @Benchmark
  @Group("withExpWait1Rnd")
  def withExpWait1Rnd(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithExpWait1Rnd))
  }

  @Benchmark
  @Group("withLongExpWait1")
  def withLongExpWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithLongExpWait1))
  }

  @Benchmark
  @Group("withLongExpWait1Rnd")
  def withLongExpWait1Rnd(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithLongExpWait1Rnd))
  }

  @Benchmark
  @Group("withOnSpinWait2")
  def withOnSpinWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithOnSpinWait2))
  }

  @Benchmark
  @Group("withOnSpinWait2")
  def withOnSpinWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithOnSpinWait2))
  }

  @Benchmark
  @Group("withExpWait2")
  def withExpWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithExpWait2))
  }

  @Benchmark
  @Group("withExpWait2")
  def withExpWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithExpWait2))
  }

  @Benchmark
  @Group("withExpWait2Rnd")
  def withExpWait2RndForward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithExpWait2Rnd))
  }

  @Benchmark
  @Group("withExpWait2Rnd")
  def withExpWait2RndBackward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithExpWait2Rnd))
  }

  @Benchmark
  @Group("withLongExpWait2")
  def withLongExpWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithLongExpWait2))
  }

  @Benchmark
  @Group("withLongExpWait2")
  def withLongExpWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithLongExpWait2))
  }

  @Benchmark
  @Group("withLongExpWait2Rnd")
  def withLongExpWait2RndForward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithLongExpWait2Rnd))
  }

  @Benchmark
  @Group("withLongExpWait2Rnd")
  def withLongExpWait2RndBackward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal(s.mcasImpl.currentContext(), s.rsWithLongExpWait2Rnd))
  }
}

object SpinBench {

  @State(Scope.Benchmark)
  class SpinSt extends McasImplStateBase {

    import RetryStrategy.Spin

    private[this] final val LONG_WAIT = 256

    val rsWithOnSpinWait1: Spin = RetryStrategy.Default.withMaxSpin(1).withRandomizeSpin(false)
    val rsWithExpWait1: Spin = RetryStrategy.Default.withMaxSpin(16).withRandomizeSpin(false)
    val rsWithExpWait1Rnd: Spin = RetryStrategy.Default.withMaxSpin(16).withRandomizeSpin(true)
    val rsWithLongExpWait1: Spin = RetryStrategy.Default.withMaxSpin(LONG_WAIT).withRandomizeSpin(false)
    val rsWithLongExpWait1Rnd: Spin = RetryStrategy.Default.withMaxSpin(LONG_WAIT).withRandomizeSpin(true)
    val rsWithOnSpinWait2: Spin = RetryStrategy.Default.withMaxSpin(1).withRandomizeSpin(false)
    val rsWithExpWait2: Spin = RetryStrategy.Default.withMaxSpin(16).withRandomizeSpin(false)
    val rsWithExpWait2Rnd: Spin = RetryStrategy.Default.withMaxSpin(16).withRandomizeSpin(true)
    val rsWithLongExpWait2: Spin = RetryStrategy.Default.withMaxSpin(LONG_WAIT).withRandomizeSpin(false)
    val rsWithLongExpWait2Rnd: Spin = RetryStrategy.Default.withMaxSpin(LONG_WAIT).withRandomizeSpin(true)

    val ref: Ref[String] = Ref.unsafePadded("0", this.mcasImpl.currentContext().refIdGen)
    val single: Rxn[String] = ref.getAndUpdate(mod)

    val ref1: Ref[String] = Ref.unsafePadded("0", this.mcasImpl.currentContext().refIdGen)
    val ref2: Ref[String] = Ref.unsafePadded("0", this.mcasImpl.currentContext().refIdGen)
    val forward: Rxn[(String, String)] = (ref1.getAndUpdate(mod) * ref2.getAndUpdate(mod))
    val backward: Rxn[(String, String)] = (ref2.getAndUpdate(mod) * ref1.getAndUpdate(mod))

    private[this] final def mod(s: String): String =
      (s + s.length.toString).takeRight(4)
  }
}
