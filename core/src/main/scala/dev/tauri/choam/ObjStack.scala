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

import java.util.Arrays

import scala.reflect.ClassTag
import scala.collection.immutable.ArraySeq

private final class ObjStack[A](initSize: Int) {

  require(initSize > 0)
  require((initSize & (initSize - 1)) == 0) // power of 2

  private[this] var size: Int =
    0

  private[this] var arr: Array[AnyRef] =
    Array.ofDim[AnyRef](initSize)(ClassTag.AnyRef)

  final override def toString: String = {
    s"ObjStack(${List(ArraySeq.unsafeWrapArray(Arrays.copyOf(this.arr, this.size)): _*).reverse.mkString(", ")})"
  }

  def push(a: A): Unit = {
    this.growIfNecessary()
    this.arr(this.size) = a.asInstanceOf[AnyRef]
    this.size += 1
  }

  private[this] def assertNonEmpty(): Unit = {
    if (this.size == 0) {
      throw new NoSuchElementException
    }
  }

  def pop(): A = {
    assertNonEmpty()
    // introducing these 2 locals makes the method bytecode smaller:
    val newSize = this.size - 1
    val arr = this.arr
    this.size = newSize
    val res: A = arr(newSize).asInstanceOf[A]
    arr(newSize) = null
    res
  }

  def top(): A = {
    assertNonEmpty()
    this.arr(this.size - 1).asInstanceOf[A]
  }

  def clear(): Unit = {
    Arrays.fill(this.arr, 0, this.size, null)
    this.size = 0
  }

  def isEmpty: Boolean = {
    this.size == 0
  }

  def nonEmpty: Boolean = {
    !this.isEmpty
  }

  def toArray(): Array[A] = {
    Arrays.copyOf(this.arr, this.size).asInstanceOf[Array[A]]
  }

  def pushAll(as: Iterable[A]): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next())
    }
  }

  def replaceWith(that: Array[A]): Unit = {
    this.replaceWithUnsafe(that.asInstanceOf[Array[Any]])
  }

  // Note: we treat `that` as if it's immutable.
  def replaceWithUnsafe(that: Array[Any]): Unit = {
    while (that.length > this.arr.length) {
      this.grow()
    }
    // that.length <= this.arr.length
    System.arraycopy(that, 0, this.arr, 0, that.length)
    Arrays.fill(this.arr, that.length, this.arr.length, null)
    this.size = that.length
  }

  private[this] def growIfNecessary(): Unit = {
    if (this.size == this.arr.length) {
      this.grow()
    }
  }

  private[this] def grow(): Unit = {
    val newArr = new Array[AnyRef](this.arr.length << 1)
    System.arraycopy(this.arr, 0, newArr, 0, this.size)
    this.arr = newArr
  }
}
