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
package core
package bench

import java.util.concurrent.ThreadLocalRandom

import scala.collection.mutable

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import dev.tauri.choam.bench.util.RandomState

@Fork(1)
@Threads(1)
private[core] class InternalStackBench {

  import InternalStackBench._

  // push/pop:

  @Benchmark
  def objStack_pushPop_list(s: ListObjSt, bh: Blackhole, r: RandomState): Unit = {
    _pushPop_objSt(s, bh, r)
  }

  @Benchmark
  def objStack_pushPop_array(s: ArrayObjSt, bh: Blackhole, r: RandomState): Unit = {
    _pushPop_objSt(s, bh, r)
  }

  @Benchmark
  def objStack_pushPop_scala(s: ScSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.scalaStack.isEmpty) {
      s.scalaStack.push("x")
    } else {
      bh.consume(s.scalaStack.pop())
    }
  }

  private[this] final def _pushPop_objSt(s: ObjSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.abstractObjStack.isEmpty()) {
      s.abstractObjStack.push("x")
    } else {
      bh.consume(s.abstractObjStack.pop())
    }
  }

  // push/pop twice:

  @Benchmark
  def objStack_pushPop2_list(s: ListObjSt, bh: Blackhole, r: RandomState): Unit = {
    _pushPop2_objSt(s, bh, r)
  }

  @Benchmark
  def objStack_pushPop2_list_baseline(s: ListObjSt, bh: Blackhole, r: RandomState): Unit = {
    _pushPop2_objSt_baseline(s, bh, r)
  }

  @Benchmark
  def objStack_pushPop2_array(s: ArrayObjSt, bh: Blackhole, r: RandomState): Unit = {
    _pushPop2_objSt(s, bh, r)
  }

  @Benchmark
  def objStack_pushPop2_array_baseline(s: ArrayObjSt, bh: Blackhole, r: RandomState): Unit = {
    _pushPop2_objSt_baseline(s, bh, r)
  }

  private[this] final def _pushPop2_objSt(s: ObjSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.abstractObjStack.isEmpty()) {
      s.abstractObjStack.push2("x", "y")
      bh.consume(s.abstractObjStack.pop())
    } else {
      s.abstractObjStack.push("x")
      bh.consume(s.abstractObjStack.pop())
      bh.consume(s.abstractObjStack.pop())
    }
  }

  private[this] final def _pushPop2_objSt_baseline(s: ObjSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.abstractObjStack.isEmpty()) {
      s.abstractObjStack.push("x")
      s.abstractObjStack.push("y")
      bh.consume(s.abstractObjStack.pop())
    } else {
      s.abstractObjStack.push("x")
      bh.consume(s.abstractObjStack.pop())
      bh.consume(s.abstractObjStack.pop())
    }
  }

  // load/take snapshot:

  @Benchmark
  def objStack_addAllSnapshot_list(s: ListObjSt, r: RandomArray): ListObjStack.Lst[String] = {
    s.listObjStack.loadSnapshot(r.randomList)
    s.listObjStack.takeSnapshot()
  }

  // load snapshot / clear:

  @Benchmark
  def objStack_addAllClear_list(s: ListObjSt, r: RandomArray): Unit = {
    s.listObjStack.loadSnapshot(r.randomList)
    s.listObjStack.clear()
  }

  // ByteStack:

  @Benchmark
  def byteStack_pushPop(s: ByteSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.byteStack.isEmpty()) {
      s.byteStack.push(r.nextInt().toByte)
    } else {
      bh.consume(s.byteStack.pop())
    }
  }

  @Benchmark
  def byteStack_pushPop2(s: ByteSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.byteStack.isEmpty()) {
      s.byteStack.push2(r.nextInt().toByte, r.nextInt().toByte)
      bh.consume(s.byteStack.pop())
    } else {
      s.byteStack.push(r.nextInt().toByte)
      bh.consume(s.byteStack.pop())
      bh.consume(s.byteStack.pop())
    }
  }

  @Benchmark
  def byteStack_pushPop2_baseline(s: ByteSt, bh: Blackhole, r: RandomState): Unit = {
    if (r.nextBoolean() || s.byteStack.isEmpty()) {
      s.byteStack.push(r.nextInt().toByte)
      s.byteStack.push(r.nextInt().toByte)
      bh.consume(s.byteStack.pop())
    } else {
      s.byteStack.push(r.nextInt().toByte)
      bh.consume(s.byteStack.pop())
      bh.consume(s.byteStack.pop())
    }
  }
}

private object InternalStackBench {

  final val initSize = 16

  @State(Scope.Thread)
  class ByteSt {

    val byteStack: ByteStack = {
      val s = new ByteStack(initSize * 2)
      for (_ <- 1 to initSize) {
        s.push(ThreadLocalRandom.current().nextInt().toByte)
      }
      s
    }
  }

  @State(Scope.Thread)
  abstract class ObjSt {
    def abstractObjStack: ObjStack[String]
  }

  @State(Scope.Thread)
  class ListObjSt extends ObjSt {

    override def abstractObjStack: ObjStack[String] =
      this.listObjStack

    val listObjStack: ListObjStack[String] = {
      val s = new ListObjStack[String]
      for (i <- 1 to initSize) {
        s.push(i.toString())
      }
      s
    }
  }

  @State(Scope.Thread)
  class ArrayObjSt extends ObjSt {

    override def abstractObjStack: ObjStack[String] =
      this.arrayObjStack

    val arrayObjStack: ArrayObjStack[String] = {
      val s = new ArrayObjStack[String](initSize = initSize)
      for (i <- 1 to initSize) {
        s.push(i.toString())
      }
      s
    }
  }

  @State(Scope.Thread)
  class ScSt {
    val scalaStack: mutable.Stack[String] = {
      val s = new scala.collection.mutable.Stack[String](initialSize = initSize)
      for (i <- 1 to initSize) {
        s.push(i.toString())
      }
      s
    }
  }

  @State(Scope.Thread)
  class RandomArray {
    val randomArray: Array[String] = {
      Array.fill(8 + ThreadLocalRandom.current().nextInt(32)) {
        ThreadLocalRandom.current().nextLong().toString()
      }
    }
    val randomList: ListObjStack.Lst[String] = {
      val l = List.fill(8 + ThreadLocalRandom.current().nextInt(32)) {
        ThreadLocalRandom.current().nextLong().toString()
      }
      val s = new ListObjStack[String]
      s.pushAll(l)
      s.takeSnapshot()
    }
  }
}
