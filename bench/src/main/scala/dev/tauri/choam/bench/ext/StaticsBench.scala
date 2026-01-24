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
package ext

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ThreadLocalRandom

@Fork(2)
class StaticsBench {

  import StaticsBench._

  @Benchmark
  def packageObjectObject(t: ThSt, bh: Blackhole): Unit = {
    bh.consume(packageObject[String](t.nextString()))
  }

  @Benchmark
  def packageObjectPrimitive(t: ThSt, bh: Blackhole): Unit = {
    bh.consume(packageObject[Int](t.nextInt()))
  }

  private def packageObject[A](a: A): Boolean = {
    _root_.dev.tauri.choam.equ[A](a, _root_.dev.tauri.choam.nullOf[A])
  }

  @Benchmark
  def javaStaticObject(t: ThSt, bh: Blackhole): Unit = {
    bh.consume(javaStatic[String](t.nextString()))
  }

  @Benchmark
  def javaStaticPrimitive(t: ThSt, bh: Blackhole): Unit = {
    bh.consume(javaStatic[Int](t.nextInt()))
  }

  private def javaStatic[A](a: A): Boolean = {
    Statics.equ[A](a, Statics.nullOf[A])
  }
}

object StaticsBench {

  @State(Scope.Thread)
  class ThSt {

    var tlr: ThreadLocalRandom = null

    @Setup
    def setUp(): Unit = {
      this.tlr = ThreadLocalRandom.current()
    }

    def nextString(): String = {
      val i = this.tlr.nextInt(64)
      if ((i % 8) == 0) {
        null
      } else {
        Integer.toString(i)
      }
    }

    def nextInt(): Int = {
      this.tlr.nextInt()
    }
  }
}
