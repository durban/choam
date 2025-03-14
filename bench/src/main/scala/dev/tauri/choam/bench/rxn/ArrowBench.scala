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
package rxn

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

@Fork(3)
class ArrowBench {

  @Benchmark
  def onlyComputed(s: ArrowBench.St, bh: Blackhole, k: McasImplState, r: RandomState): Unit = {
    val ref = s.refs(Math.abs(r.nextInt()) % ArrowBench.size)
    bh.consume(s.rOnlyComputed(ref).unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def withoutComputed(s: ArrowBench.St, bh: Blackhole, k: McasImplState, r: RandomState): Unit = {
    val ref = s.refs(Math.abs(r.nextInt()) % ArrowBench.size)
    bh.consume(s.rWithoutComputed(ref).unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def updPrimitive(s: ArrowBench.USt, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val r = s.updPrimitive(s.refs(Math.abs(rnd.nextInt()) % ArrowBench.size))
    bh.consume(r.unsafePerform(rnd.nextString(), k.mcasImpl))
  }
}

object ArrowBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St extends McasImplStateBase {

    val refs: List[Ref[String]] = List.fill(size) {
      Ref.unsafePadded[String](
        ThreadLocalRandom.current().nextInt().toString,
        this.mcasImpl.currentContext().refIdGen,
      )
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
  class USt extends McasImplStateBase {

    val refs: List[Ref[Long]] = List.fill(size) {
      Ref.unsafePadded[Long](
        ThreadLocalRandom.current().nextLong(),
        this.mcasImpl.currentContext().refIdGen,
      )
    }

    def updPrimitive(ref: Ref[Long]): Rxn[String, Long] = {
      Rxn.ref.upd[Long, String, Long](ref) { (i, s) => (i + 1, s.length.toLong) }
    }
  }
}
