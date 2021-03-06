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

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import kcas.Ref
import util._
import org.openjdk.jmh.infra.Blackhole

@Fork(2)
class ArrowBench {

  final val size = 4096

  @Benchmark
  def onlyComputed(s: ArrowBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val ref = s.refs(Math.abs(k.nextInt()) % ArrowBench.size)
    bh.consume(s.rOnlyComputed(ref).unsafeRun(k.kcasImpl))
  }

  @Benchmark
  def withoutComputed(s: ArrowBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val ref = s.refs(Math.abs(k.nextInt()) % ArrowBench.size)
    bh.consume(s.rWithoutComputed(ref).unsafeRun(k.kcasImpl))
  }

  @Benchmark
  def updDerived(s: ArrowBench.USt, bh: Blackhole, k: KCASImplState): Unit = {
    val r = React.updDerived[Long, String, Long](s.ref) { (i, s) => (i + 1, s.length.toLong) }
    bh.consume(r.unsafePerform(k.nextString(), k.kcasImpl))
  }

  @Benchmark
  def updPrimitive(s: ArrowBench.USt, bh: Blackhole, k: KCASImplState): Unit = {
    val r = React.upd[Long, String, Long](s.ref) { (i, s) => (i + 1, s.length.toLong) }
    bh.consume(r.unsafePerform(k.nextString(), k.kcasImpl))
  }
}

object ArrowBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St {

    val refs = List.fill(size) {
      Ref.mk[String](ThreadLocalRandom.current().nextInt().toString)
    }

    def rWithoutComputed(ref: Ref[String]): React[Unit, Int] =
      ref.getter.map(_.toUpperCase).map(_.trim).map(_.length)

    def rOnlyComputed(ref: Ref[String]): React[Unit, Int] = {
      ref.getter.flatMap { s =>
        React.ret(s.toUpperCase).flatMap { u =>
          React.ret(u.trim).flatMap { t =>
            React.ret(t.length)
          }
        }
      }
    }
  }

  @State(Scope.Benchmark)
  class USt {
    val ref = Ref.mk[Long](0L)
  }
}
