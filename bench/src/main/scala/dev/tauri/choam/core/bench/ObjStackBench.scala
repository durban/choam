/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

@Fork(1)
@Threads(1)
class ObjStackBench {

  import ObjStackBench._

  @Benchmark
  def pushPopListObjStack(s: ListObjSt, bh: Blackhole): Unit = {
    if (ThreadLocalRandom.current().nextBoolean() || s.listObjStack.isEmpty) {
      s.listObjStack.push("x")
    } else {
      bh.consume(s.listObjStack.pop())
    }
  }

  @Benchmark
  def pushPopArrayObjStack(s: ArrayObjSt, bh: Blackhole): Unit = {
    if (ThreadLocalRandom.current().nextBoolean() || s.arrayObjStack.isEmpty) {
      s.arrayObjStack.push("x")
    } else {
      bh.consume(s.arrayObjStack.pop())
    }
  }

  @Benchmark
  def pushPopScalaStack(s: ScSt, bh: Blackhole): Unit = {
    if (ThreadLocalRandom.current().nextBoolean() || s.scalaStack.isEmpty) {
      s.scalaStack.push("x")
    } else {
      bh.consume(s.scalaStack.pop())
    }
  }

  @Benchmark
  def toArrayListObjStack(s: ListObjSt, bh: Blackhole): Unit = {
    bh.consume(s.listObjStack.takeSnapshot())
  }

  @Benchmark
  def toArrayScalaStack(s: ScSt, bh: Blackhole): Unit = {
    bh.consume(s.scalaStack.toArray)
  }

  @Benchmark
  def addAllClearListObjStack(s: ListObjSt, r: RandomArray): Unit = {
    s.listObjStack.loadSnapshot(r.randomList)
    s.listObjStack.clear()
  }

  @Benchmark
  def addAllClearScalaStack(s: ScSt, r: RandomArray): Unit = {
    s.scalaStack.addAll(r.randomArray)
    s.scalaStack.clear()
  }
}

private object ObjStackBench {

  final val initSize = 8

  @State(Scope.Thread)
  class ListObjSt {
    val listObjStack: ListObjStack[String] = {
      val s = new ListObjStack[String]
      for (i <- 1 to 8) {
        if (ThreadLocalRandom.current().nextBoolean()) {
          s.push(i.toString())
        }
      }
      s
    }
  }

  @State(Scope.Thread)
  class ArrayObjSt {
    val arrayObjStack: ObjStack[String] = {
      val s = new ArrayObjStack[String]
      for (i <- 1 to 8) {
        if (ThreadLocalRandom.current().nextBoolean()) {
          s.push(i.toString())
        }
      }
      s
    }
  }

  @State(Scope.Thread)
  class ScSt {
    val scalaStack: mutable.Stack[String] = {
      val s = new scala.collection.mutable.Stack[String](initialSize = initSize)
      for (i <- 1 to 8) {
        if (ThreadLocalRandom.current().nextBoolean()) {
          s.push(i.toString())
        }
      }
      s
    }
  }

  @State(Scope.Thread)
  class RandomArray {
    val randomArray: Array[String] = {
      Array.fill(ThreadLocalRandom.current().nextInt(16)) {
        ThreadLocalRandom.current().nextLong().toString()
      }
    }
    val randomList: ListObjStack.Lst[String] = {
      val l = List.fill(ThreadLocalRandom.current().nextInt(16)) {
        ThreadLocalRandom.current().nextLong().toString()
      }
      val s = new ListObjStack[String]
      s.pushAll(l)
      s.takeSnapshot()
    }
  }
}
