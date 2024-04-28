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

private final class ArrayObjStack[A]() extends ObjStack[A] {

  // TODO: ObjStackBench.pushPop* is faster for ArrayObjStack
  // TODO: than for ListObjStack. However, InternalStackBench
  // TODO: is the opposite. Figure out which one of these
  // TODO: matters more in practice.

  private[this] val arr =
    new scala.collection.mutable.ArrayDeque[A]

  final override def toString: String = {
    this.arr.reverse.mkString("ArrayObjStack(", ", ", ")")
  }

  final override def push(a: A): Unit = {
    this.arr.addOne(a)
  }

  final override def pop(): A = {
    this.arr.removeLast()
  }

  final override def peek(): A = {
    this.arr.last
  }

  final override def peekSecond(): A = {
    val arr = this.arr
    arr.apply(arr.length - 2)
  }

  final override def clear(): Unit = {
    this.arr.clear()
  }

  final override def isEmpty: Boolean = {
    this.arr.isEmpty
  }

  final override def nonEmpty: Boolean = {
    this.arr.nonEmpty
  }

  final def toListObjStack(): ListObjStack[A] = {
    val arr = this.arr
    var lst = ListObjStack.Lst.empty[A]
    var idx = 0
    val len = arr.length
    while (idx < len) {
      lst = ListObjStack.Lst(head = arr.apply(idx), tail = lst)
      idx += 1
    }
    val r = new ListObjStack[A]
    r.loadSnapshot(lst)
    r
  }
}
