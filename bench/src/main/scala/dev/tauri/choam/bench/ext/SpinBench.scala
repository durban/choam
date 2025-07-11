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
import core.{ Rxn, Ref }
import SpinBench._

@Fork(3)
class SpinBench {

  private[this] final val LONG_WAIT = 256

  @Benchmark
  @Group("baseline1")
  def baseline1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 0, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withOnSpinWait1")
  def withOnSpinWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 1, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait1")
  def withExpWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 16, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait1Rnd")
  def withExpWait1Rnd(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 16, randomizeBackoff = true))
  }

  @Benchmark
  @Group("withLongExpWait1")
  def withLongExpWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = LONG_WAIT, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withLongExpWait1Rnd")
  def withLongExpWait1Rnd(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = LONG_WAIT, randomizeBackoff = true))
  }

  @Benchmark
  @Group("baseline2")
  def baseline2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 0, randomizeBackoff = false))
  }

  @Benchmark
  @Group("baseline2")
  def baseline2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 0, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withOnSpinWait2")
  def withOnSpinWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 1, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withOnSpinWait2")
  def withOnSpinWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 1, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait2")
  def withExpWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 16, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait2")
  def withExpWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 16, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait2Rnd")
  def withExpWait2RndForward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 16, randomizeBackoff = true))
  }

  @Benchmark
  @Group("withExpWait2Rnd")
  def withExpWait2RndBackward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = 16, randomizeBackoff = true))
  }

  @Benchmark
  @Group("withLongExpWait2")
  def withLongExpWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = LONG_WAIT, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withLongExpWait2")
  def withLongExpWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = LONG_WAIT, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withLongExpWait2Rnd")
  def withLongExpWait2RndForward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = LONG_WAIT, randomizeBackoff = true))
  }

  @Benchmark
  @Group("withLongExpWait2Rnd")
  def withLongExpWait2RndBackward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerformInternal((), s.mcasImpl.currentContext(), maxBackoff = LONG_WAIT, randomizeBackoff = true))
  }
}

object SpinBench {

  @State(Scope.Benchmark)
  class SpinSt extends McasImplStateBase {

    val ref: Ref[String] = Ref.unsafePadded("0", this.mcasImpl.currentContext().refIdGen)
    val single: Rxn[String] = ref.getAndUpdate(mod)

    val ref1: Ref[String] = Ref.unsafePadded("0", this.mcasImpl.currentContext().refIdGen)
    val ref2: Ref[String] = Ref.unsafePadded("0", this.mcasImpl.currentContext().refIdGen)
    val forward: Rxn[(String, String)] = (ref1.getAndUpdate(mod) * ref2.getAndUpdate(mod))
    val backward: Rxn[(String, String)] = (ref2.getAndUpdate(mod) * ref1.getAndUpdate(mod))

    def mod(s: String): String =
      (s + s.length.toString).takeRight(4)
  }
}
