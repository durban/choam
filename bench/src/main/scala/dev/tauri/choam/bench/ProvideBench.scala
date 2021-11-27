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

import util._

/** Compares the performance of `x.provide(a)` and `x.contramap(_ => a)` */
@Fork(3)
class ProvideBench {

  @Benchmark
  def provide(s: ProvideBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(s.provide.unsafePerformInternal((), k.kcasCtx))
  }

  @Benchmark
  def contramap(s: ProvideBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(s.contramap.unsafePerformInternal((), k.kcasCtx))
  }
}

object ProvideBench {

  @State(Scope.Benchmark)
  class St {
    val ref: Ref[String] =
      Ref.unsafe("a")
    val provide: Axn[String] =
      ref.getAndSet.provide("x")
    val contramap: Axn[String] =
      ref.getAndSet.provideOld("x")
  }
}
