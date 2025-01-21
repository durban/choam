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

import java.util.Arrays

private final class ArrayObjStack[A]() extends ObjStack[A] {

  private[this] var arr: Array[AnyRef] =
    new Array[AnyRef](16)

  private[this] var size: Int =
    0

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
      val newLength = nextPowerOf2Internal(s)
      val newArr = Arrays.copyOf(arr, newLength)
      this.arr = newArr
    }
  }

  /**
   * Computes a power of 2 which is `>= n`.
   *
   * Assumes `x` is non-negative (an array length).
   *
   * From Hacker's Delight by Henry S. Warren, Jr. (section 3–2).
   */
  private[this] def nextPowerOf2Internal(n: Int): Int = { // TODO: this is duplicated with ByteStack
    var x: Int = n - 1
    x |= x >> 1
    x |= x >> 2
    x |= x >> 4
    x |= x >> 8
    x |= x >> 16
    x + 1
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
