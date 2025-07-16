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

import internal.mcas.Consts

import java.util.Arrays

private final class ArrayObjStack[A](initSize: Int) extends ObjStack[A] {

  require((initSize > 0) && (initSize <= ArrayObjStack.maxSize) && ((initSize & (initSize - 1)) == 0)) // power of 2

  private[this] var arr: Array[AnyRef] =
    new Array[AnyRef](initSize)

  private[this] var size: Int =
    0

  final def length: Int =
    this.size

  final override def toString: String = {
    this.arr.take(this.size).reverse.mkString("ArrayObjStack(", ", ", ")")
  }

  final override def push(a: A): Unit = {
    val currSize = this.size
    val newSize = currSize + 1
    this.ensureSize(newSize)
    this.arr(currSize) = box(a)
    this.size = newSize
  }

  final override def push2(a1: A, a2: A): Unit = {
    val currSize = this.size
    val newSize = currSize + 2
    this.ensureSize(newSize)
    this.arr(currSize) = box(a1)
    this.arr(currSize + 1) = box(a2)
    this.size = newSize
  }

  final override def push3(a1: A, a2: A, a3: A): Unit = {
    val currSize = this.size
    val newSize = currSize + 3
    this.ensureSize(newSize)
    this.arr(currSize) = box(a1)
    this.arr(currSize + 1) = box(a2)
    this.arr(currSize + 2) = box(a3)
    this.size = newSize
  }

  private[this] final def ensureSize(s: Int): Unit = {
    val arr = this.arr
    if (s > arr.length) {
      if (s > ArrayObjStack.maxSize) {
        // we're trying to have more than 256M items
        // on the stack, so something is seriously
        // wrong; `nextPowerOf2` below would overflow
        // after 2 more doubling, but we're giving
        // up earlier (we already have a 256M-long
        // array, that's 1GiB even with CompressedOops,
        // there is no way that's normal...)
        throw new AssertionError
      }
      val newLength = Consts.nextPowerOf2(s)
      val newArr = Arrays.copyOf(arr, newLength)
      this.arr = newArr
    }
  }

  final override def pop(): A = {
    val currSize = this.size
    require(currSize > 0)
    val newSize = currSize - 1
    val arr = this.arr
    val a = arr(newSize)
    arr(newSize) = null
    this.size = newSize
    a.asInstanceOf[A]
  }

  final override def peek(): A = {
    val currSize = this.size
    require(currSize > 0)
    this.arr(currSize - 1).asInstanceOf[A]
  }

  final override def peekSecond(): A = {
    val currSize = this.size
    require(currSize > 1)
    this.arr(currSize - 2).asInstanceOf[A]
  }

  final override def clear(): Unit = {
    Arrays.fill(this.arr, 0, this.size, null)
    this.size = 0
  }

  final override def isEmpty(): Boolean = {
    this.size == 0
  }

  final override def nonEmpty(): Boolean = {
    this.size != 0
  }

  final def toListObjStack(): ListObjStack[A] = {
    val arr = this.arr
    var lst = ListObjStack.Lst.empty[A]
    var idx = 0
    val len = this.size
    while (idx < len) {
      lst = ListObjStack.Lst(head = arr(idx).asInstanceOf[A], tail = lst)
      idx += 1
    }
    val r = new ListObjStack[A]
    r.loadSnapshot(lst)
    r
  }
}

private object ArrayObjStack {

  final val maxSize = 256 * 1024 * 1024
}
