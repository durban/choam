/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.collection.immutable.ArraySeq

private final class ByteStack(initSize: Int) {

  require(initSize > 0)
  require((initSize & (initSize - 1)) == 0) // power of 2

  private[this] var size: Int =
    0

  private[this] var arr: Array[Byte] =
    new Array[Byte](initSize)

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
    // introducing this local makes the method bytecode smaller:
    val newSize = this.size - 1
    this.size = newSize
    this.arr(newSize)
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

  def takeSnapshot(): Array[Byte] = {
    Arrays.copyOf(this.arr, this.size)
  }

  /** Note: we treat `snapshot` as if it's immutable */
  def loadSnapshot(snapshot: Array[Byte]): Unit = {
    val snapLength = snapshot.length
    val newLength = nextPowerOf2Internal(snapLength)
    this.arr = Arrays.copyOf(snapshot, newLength)
    this.size = snapLength
  }

  /**
   * Computes a power of 2 which is `>= n`.
   *
   * Assumes `x` is non-negative (an array length)
   *
   * From Hacker's Delight by Henry S. Warren, Jr. (section 3–2).
   */
  private[this] def nextPowerOf2Internal(n: Int): Int = {
    var x: Int = n - 1
    x |= x >> 1
    x |= x >> 2
    x |= x >> 4
    x |= x >> 8
    x |= x >> 16
    x + 1
  }

  /** For testing */
  private[core] def nextPowerOf2(n: Int): Int = {
    require(n >= 0)
    val res = nextPowerOf2Internal(n)
    assert(res >= 0)
    res
  }

  private[this] def growIfNecessary(): Unit = {
    if (this.size == this.arr.length) {
      this.grow()
    }
  }

  private[this] def grow(): Unit = {
    val newArr = new Array[Byte](this.arr.length << 1)
    System.arraycopy(this.arr, 0, newArr, 0, this.size)
    this.arr = newArr
  }
}

private final object ByteStack {

  def splitAt(arr: Array[Byte], idx: Int): (Array[Byte], Array[Byte]) = {
    require(idx >= 0)
    require(idx <= arr.length)
    val a = Arrays.copyOfRange(arr, 0, idx)
    val b = Arrays.copyOfRange(arr, idx, arr.length)
    (a, b)
  }

  def push(arr: Array[Byte], item: Byte): Array[Byte] = {
    val res = new Array[Byte](arr.length + 1)
    System.arraycopy(arr, 0, res, 0, arr.length)
    res(arr.length) = item
    res
  }
}
