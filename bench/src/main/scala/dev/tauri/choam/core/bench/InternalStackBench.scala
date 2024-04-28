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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(2)
@Threads(1)
class InternalStackBench {

  final val N = 512

  @Benchmark
  def byteStack(bh: Blackhole): Unit = {
    val byteStack = new ByteStack(initSize = 8)
    var i = 0
    while (i < N) {
      byteStack.push(42)
      i += 1
    }
    i = 0
    while (i < N) {
      bh.consume(byteStack.pop())
      i += 1
    }
    i = 0
    while (i < N) {
      byteStack.push(42)
      i += 1
    }
  }

  @Benchmark
  def listObjStack(bh: Blackhole): Unit = {
    val listObjStack: ListObjStack[String] = new ListObjStack[String]
    var i = 0
    while (i < N) {
      listObjStack.push("test")
      i += 1
    }
    i = 0
    while (i < N) {
      bh.consume(listObjStack.pop())
      i += 1
    }
    i = 0
    while (i < N) {
      listObjStack.push("test")
      i += 1
    }
  }

  @Benchmark
  def listObjStackAbstract(bh: Blackhole): Unit = {
    val listObjStackAbstract: ObjStack[String] = new ListObjStack[String]
    var i = 0
    while (i < N) {
      listObjStackAbstract.push("test")
      i += 1
    }
    i = 0
    while (i < N) {
      bh.consume(listObjStackAbstract.pop())
      i += 1
    }
    i = 0
    while (i < N) {
      listObjStackAbstract.push("test")
      i += 1
    }
  }

  @Benchmark
  def arrayObjStackAbstract(bh: Blackhole): Unit = {
    val arrayObjStackAbstract: ObjStack[String] = new ArrayObjStack[String]
    var i = 0
    while (i < N) {
      arrayObjStackAbstract.push("test")
      i += 1
    }
    i = 0
    while (i < N) {
      bh.consume(arrayObjStackAbstract.pop())
      i += 1
    }
    i = 0
    while (i < N) {
      arrayObjStackAbstract.push("test")
      i += 1
    }
  }
}
