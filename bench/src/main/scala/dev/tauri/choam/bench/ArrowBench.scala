/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

@Fork(3)
class ArrowBench {

  @Benchmark
  def onlyComputed(s: ArrowBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val ref = s.refs(Math.abs(k.nextInt()) % ArrowBench.size)
    bh.consume(s.rOnlyComputed(ref).unsafePerform((), k.kcasImpl))
  }

  @Benchmark
  def withoutComputed(s: ArrowBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val ref = s.refs(Math.abs(k.nextInt()) % ArrowBench.size)
    bh.consume(s.rWithoutComputed(ref).unsafePerform((), k.kcasImpl))
  }

  @Benchmark
  def updPrimitive(s: ArrowBench.USt, bh: Blackhole, k: KCASImplState): Unit = {
    val r = s.updPrimitive(s.refs(Math.abs(k.nextInt()) % ArrowBench.size))
    bh.consume(r.unsafePerform(k.nextString(), k.kcasImpl))
  }
}

object ArrowBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St {

    val refs: List[Ref[String]] = List.fill(size) {
      Ref.unsafe[String](ThreadLocalRandom.current().nextInt().toString)
    }

    def rWithoutComputed(ref: Ref[String]): Axn[Int] =
      ref.get.map(_.toUpperCase).map(_.trim).map(_.length)

    def rOnlyComputed(ref: Ref[String]): Axn[Int] = {
      ref.get.flatMap { s =>
        Rxn.ret(s.toUpperCase).flatMap { u =>
          Rxn.ret(u.trim).flatMap { t =>
            Rxn.ret(t.length)
          }
        }
      }
    }
  }

  @State(Scope.Benchmark)
  class USt {

    val refs: List[Ref[Long]] = List.fill(size) {
      Ref.unsafe[Long](ThreadLocalRandom.current().nextLong())
    }

    def updPrimitive(ref: Ref[Long]): Rxn[String, Long] = {
      Rxn.ref.upd[Long, String, Long](ref) { (i, s) => (i + 1, s.length.toLong) }
    }
  }
}
