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

import kcas.Ref
import util.KCASImplState
import SpinBench._

@Fork(3)
class SpinBench {

  @Benchmark
  @Group("baseline1")
  def baseline1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerform((), s.kcasImpl, maxBackoff = 0, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withOnSpinWait1")
  def withOnSpinWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerform((), s.kcasImpl, maxBackoff = 1, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait1")
  def withExpWait1(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerform((), s.kcasImpl, maxBackoff = 16, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait1Rnd")
  def withExpWait1Rnd(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.single.unsafePerform((), s.kcasImpl, maxBackoff = 16, randomizeBackoff = true))
  }

  @Benchmark
  @Group("baseline2")
  def baseline2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerform((), s.kcasImpl, maxBackoff = 0, randomizeBackoff = false))
  }

  @Benchmark
  @Group("baseline2")
  def baseline2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerform((), s.kcasImpl, maxBackoff = 0, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withOnSpinWait2")
  def withOnSpinWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerform((), s.kcasImpl, maxBackoff = 1, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withOnSpinWait2")
  def withOnSpinWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerform((), s.kcasImpl, maxBackoff = 1, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait2")
  def withExpWait2Forward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerform((), s.kcasImpl, maxBackoff = 16, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait2")
  def withExpWait2Backward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerform((), s.kcasImpl, maxBackoff = 16, randomizeBackoff = false))
  }

  @Benchmark
  @Group("withExpWait2Rnd")
  def withExpWait2RndForward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.forward.unsafePerform((), s.kcasImpl, maxBackoff = 16, randomizeBackoff = true))
  }

  @Benchmark
  @Group("withExpWait2Rnd")
  def withExpWait2RndBackward(s: SpinSt, bh: Blackhole): Unit = {
    bh.consume(s.backward.unsafePerform((), s.kcasImpl, maxBackoff = 16, randomizeBackoff = true))
  }
}

object SpinBench {

  @State(Scope.Benchmark)
  class SpinSt extends KCASImplState {

    val ref = Ref.mk("0")
    val single: React[Unit, String] = ref.modify(mod)

    val ref1 = Ref.mk("0")
    val ref2 = Ref.mk("0")
    val forward: React[Unit, (String, String)] = (ref1.modify(mod) * ref2.modify(mod))
    val backward: React[Unit, (String, String)] = (ref2.modify(mod) * ref1.modify(mod))

    def mod(s: String): String =
      (s + s.length.toString).takeRight(4)
  }
}
