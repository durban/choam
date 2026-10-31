/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package next

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import cats.{ effect => ce }

import core.Ref

import kyo.{ <, STM, TRef, Sync, Async, Abort }

import util._

@Fork(2)
@Threads(2)
class SimpleKyoBench {

  import SimpleKyoBench._

  private[this] final val N = 1024

  @Benchmark
  def contended_kyoStm(s: StContendedKyo, bh: Blackhole): Unit = {
    s.runAndBlock(
      KyoUtils.repeat(N) {
        STM.run(
          s.ref.update(_ + 1).andThen(s.ref.get)
        ).map { i =>
          Sync.defer(bh.consume(i))
        }
      }
    )
  }

  @Benchmark
  def contended_rxnOnCe(s: StContendedRxnOnCe, bh: Blackhole): Unit = {
    s.runAndBlock(
      (
        s.ref.update(_ + 1) *> s.ref.get
      ).run[ce.IO](using s.reactive).flatMap { i =>
        ce.IO { bh.consume(i) }
      }.replicateA_(N)
    )
  }

  @Benchmark
  def contended_rxnOnKyo(s: StContendedRxnOnKyo, bh: Blackhole): Unit = {
    s.runAndBlock(
      KyoUtils.repeat(N) {
        (
          s.ref.update(_ + 1) *> s.ref.get
        ).run[<[*, Async & Abort[Throwable]]](using s.reactive).flatMap { i =>
          Sync.defer { bh.consume(i) }
        }
      }
    )
  }

  ///

  @Benchmark
  def uncontended_kyoStm(s: StUncontendedKyo, bh: Blackhole): Unit = {
    s.runAndBlock(
      KyoUtils.repeat(N) {
        STM.run(
          s.ref.update(_ + 1).andThen(s.ref.get)
        ).map { i =>
          Sync.defer(bh.consume(i))
        }
      }
    )
  }

  @Benchmark
  def uncontended_rxnOnCe(s: StUncontendedRxnOnCe, bh: Blackhole): Unit = {
    s.runAndBlock(
      (
        s.ref.update(_ + 1) *> s.ref.get
      ).run[ce.IO](using s.reactive).flatMap { i =>
        ce.IO { bh.consume(i) }
      }.replicateA_(N)
    )
  }

  @Benchmark
  def uncontended_rxnOnKyo(s: StUncontendedRxnOnKyo, bh: Blackhole): Unit = {
    s.runAndBlock(
      KyoUtils.repeat(N) {
        (
          s.ref.update(_ + 1) *> s.ref.get
        ).run[<[*, Async & Abort[Throwable]]](using s.reactive).flatMap { i =>
          Sync.defer { bh.consume(i) }
        }
      }
    )
  }
}

object SimpleKyoBench {

  import DataMapBench2.GenMcasImplStateBaseForKyo // TODO: move this somewhere

  abstract class StKyo extends GenMcasImplStateBaseForKyo {
    val ref: TRef[Int] =
      runAndBlock(TRef.init(0))
  }

  @State(Scope.Benchmark)
  class StContendedKyo extends StKyo

  @State(Scope.Thread)
  class StUncontendedKyo extends StKyo

  abstract class StRxn[F[_]] extends GenMcasImplStateBase[F] {
    val ref: Ref[Int] =
      runAndBlock(Ref[Int](0).run[F])
  }

  @State(Scope.Benchmark)
  class StContendedRxnOnCe
    extends StRxn[ce.IO]
    with GenMcasImplStateBase.ForIO

  @State(Scope.Thread)
  class StUncontendedRxnOnCe
    extends StRxn[ce.IO]
    with GenMcasImplStateBase.ForIO

  @State(Scope.Benchmark)
  class StContendedRxnOnKyo
    extends StRxn[<[*, Async & Abort[Throwable]]]
    with GenMcasImplStateBaseForKyo

  @State(Scope.Thread)
  class StUncontendedRxnOnKyo
    extends StRxn[<[*, Async & Abort[Throwable]]]
    with GenMcasImplStateBaseForKyo
}
