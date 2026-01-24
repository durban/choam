/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import internal.mcas.Consts

private final class ByteStack(initSize: Int) {

  require((initSize > 0) && ((initSize & (initSize - 1)) == 0)) // power of 2

  private[this] var size: Int =
    0

  private[this] var arr: Array[Byte] =
    new Array[Byte](initSize)

  final override def toString: String = {
    s"ByteStack(${List(ArraySeq.unsafeWrapArray(Arrays.copyOf(this.arr, this.size))*).reverse.mkString(", ")})"
  }

  def push(b: Byte): Unit = {
    val currSize = this.size
    val newSize = currSize + 1
    this.growIfNecessary(sizeNeeded = newSize)
    this.arr(currSize) = b
    this.size = newSize
  }

  def push2(b1: Byte, b2: Byte): Unit = {
    val currSize = this.size
    val newSize = currSize + 2
    this.growIfNecessary(sizeNeeded = newSize)
    val arr = this.arr
    arr(currSize) = b1
    arr(currSize + 1) = b2
    this.size = newSize
  }

  def isEmpty(): Boolean = {
    this.size == 0
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

  def peek(): Byte = {
    assertNonEmpty()
    this.arr(this.size - 1)
  }

  def clear(): Unit = {
    this.size = 0
  }

  def nonEmpty(): Boolean = {
    !this.isEmpty()
  }

  def takeSnapshot(): Array[Byte] = {
    Arrays.copyOf(this.arr, this.size)
  }

  /** Note: we treat `snapshot` as if it's immutable */
  def loadSnapshot(snapshot: Array[Byte]): Unit = {
    val snapLength = snapshot.length
    if (snapLength == 0) {
      this.clear()
    } else {
      val newLength = Consts.nextPowerOf2(snapLength)
      this.arr = Arrays.copyOf(snapshot, newLength)
      this.size = snapLength
    }
  }

  private[this] def growIfNecessary(sizeNeeded: Int): Unit = {
    if (this.arr.length < sizeNeeded) {
      this.grow(newSize = Consts.nextPowerOf2(sizeNeeded))
    }
  }

  private[this] def grow(newSize: Int): Unit = {
    this.arr = Arrays.copyOf(this.arr, newSize)
  }
}

private object ByteStack {

  def splitAt(arr: Array[Byte], idx: Int): (Array[Byte], Array[Byte]) = {
    require(idx >= 0)
    require(idx <= arr.length)
    val a = Arrays.copyOfRange(arr, 0, idx)
    val b = Arrays.copyOfRange(arr, idx, arr.length)
    (a, b)
  }

  def push(arr: Array[Byte], item: Byte): Array[Byte] = {
    val arrLength = arr.length
    val res = Arrays.copyOf(arr, arrLength + 1)
    res(arrLength) = item
    res
  }
}
