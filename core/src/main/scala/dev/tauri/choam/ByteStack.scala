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

private final class ByteStack private (
  private[this] var arr: Array[Byte],
  private[this] var size: Int
) {

  def this(initSize: Int) = {
    this(Array.ofDim[Byte](initSize)(ClassTag.Byte), 0)
  }

  require(arr.length > 0)
  require((arr.length & (arr.length - 1)) == 0) // power of 2
  require(size >= 0)
  require(size <= arr.length)

  final override def toString: String = {
    s"ByteStack(${List(ArraySeq.unsafeWrapArray(Arrays.copyOf(this.arr, this.size)): _*).reverse.mkString(", ")})"
  }

  def push(b: Byte): Unit = {
    this.growIfNecessary()
    this.arr(this.size) = b
    this.size += 1
  }

  private[this] def assertNonEmpty(): Unit = {
    if (this.size == 0) {
      throw new NoSuchElementException
    }
  }

  def pop(): Byte = {
    assertNonEmpty()
    this.size -= 1
    this.arr(this.size)
  }

  def top(): Byte = {
    assertNonEmpty()
    this.arr(this.size - 1)
  }

  def clear(): Unit = {
    this.size = 0
  }

  def isEmpty: Boolean = {
    this.size == 0
  }

  def nonEmpty: Boolean = {
    !this.isEmpty
  }

  def toArray(): Array[Byte] = {
    Arrays.copyOfRange(this.arr, 0, this.size)
  }

  def replaceWith(that: Array[Byte]): Unit = {
    // TODO: this can make it so that arr.length is not a power of 2
    if (that.length != 0) {
      this.arr = that
      this.size = that.length
    } else {
      this.size = 0
    }
  }

  private[this] def growIfNecessary(): Unit = {
    if (this.size == this.arr.length) {
      val newArr = new Array[Byte](this.size << 1)
      System.arraycopy(this.arr, 0, newArr, 0, this.size)
      this.arr = newArr
    }
  }
}
