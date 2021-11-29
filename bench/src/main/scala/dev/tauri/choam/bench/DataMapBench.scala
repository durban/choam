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

import data.Map
import util._

@Fork(2)
@Threads(2)
class DataMapBench {

  @Benchmark
  def simple(s: DataMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    task(s.simple, bh, k)
  }

  def task(m: Map[String, String], bh: Blackhole, k: KCASImplState): Unit = {
    (k.nextInt().abs % 3) match {
      case 0 =>
        bh.consume(m.put.unsafePerformInternal((k.nextString(), k.nextString()), k.kcasCtx))
      case 1 =>
        val key = k.nextString()
        m.get.unsafePerformInternal(key, k.kcasCtx) match {
          case Some(_) =>
            bh.consume(m.put.unsafePerformInternal((key, k.nextString()), k.kcasCtx))
          case None =>
            ()
        }
      case 2 =>
        val ok = m.del.unsafePerformInternal(DataMapBench.knownKey, k.kcasCtx)
        if (!ok) {
          bh.consume(m.put.unsafePerformInternal((DataMapBench.knownKey, "x"), k.kcasCtx))
        }
      case x =>
        impossible(x.toString)
    }
  }
}

object DataMapBench {

  final val size = 8

  final val knownKey = "abcdef"

  @State(Scope.Benchmark)
  class St {
    val simple: Map[String, String] = {
      val m = Map.simple[String, String].unsafeRun(kcas.KCAS.EMCAS)
      Prefill.prefill().foreach { k =>
        m.put.unsafePerform((k, "foo"), kcas.KCAS.EMCAS)
      }
      m.put.unsafePerform((knownKey, "bar"), kcas.KCAS.EMCAS)
      m
    }
  }
}
