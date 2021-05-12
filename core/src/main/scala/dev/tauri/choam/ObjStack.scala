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

private final class ObjStack[A] private (
  private[this] var arr: Array[AnyRef],
  private[this] var size: Int
) {

  def this(initSize: Int) = {
    this(Array.ofDim[AnyRef](initSize)(ClassTag.AnyRef), 0)
  }

  require(arr.length > 0)
  require((arr.length & (arr.length - 1)) == 0) // power of 2
  require(size >= 0)
  require(size <= arr.length)

  def push(a: A): Unit = {
    this.growIfNecessary()
    this.arr(this.size) = a.asInstanceOf[AnyRef]
    this.size += 1
  }

  def pop(): A = {
    if (this.size != 0) {
      this.size -= 1
      val res: A = this.arr(this.size).asInstanceOf[A]
      this.arr(this.size) = null
      res
    } else {
      throw new NoSuchElementException(s"stack size = ${this.size}")
    }
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
    Arrays.copyOfRange(this.arr, 0, this.size).asInstanceOf[Array[A]]
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

  def replaceWithUnsafe(that: Array[Any]): Unit = {
    // TODO: this can make it so that arr.length is not a power of 2
    if (that.length != 0) {
      this.arr = that.asInstanceOf[Array[AnyRef]]
      this.size = that.length
    } else {
      Arrays.fill(this.arr, null)
      this.size = 0
    }
  }

  private[this] def growIfNecessary(): Unit = {
    if (this.size == this.arr.length) {
      val newArr = Array.ofDim[AnyRef](this.size << 1)(ClassTag.AnyRef)
      System.arraycopy(this.arr, 0, newArr, 0, this.size)
      this.arr = newArr
    }
  }
}
