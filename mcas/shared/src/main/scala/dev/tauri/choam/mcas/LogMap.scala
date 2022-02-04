/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import java.util.Arrays

import scala.collection.immutable.TreeMap
import scala.util.hashing.MurmurHash3

private abstract class LogMap {
  def size: Int
  def valuesIterator: Iterator[HalfWordDescriptor[_]]
  def nonEmpty: Boolean
  def contains[A](ref: MemoryLocation[A]): Boolean
  def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap
  def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]): HalfWordDescriptor[A]
  override def equals(that: Any): Boolean
  override def hashCode: Int
}

private object LogMap {

  final def empty: LogMap =
    Empty

  private final object Empty
    extends LogMap {

    final override def size =
      0

    final override def valuesIterator =
      Iterator.empty

    final override def nonEmpty =
      false

    final override def contains[A](ref: MemoryLocation[A]) =
      false

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap =
      new LogMap1(v)

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) =
      default

    final override def equals(that: Any): Boolean =
      equ[Any](that, this)

    final override def hashCode: Int =
      0x8de3a9b3
  }

  private final class LogMap1(private val v1: HalfWordDescriptor[_])
    extends LogMap {

    final override def size =
      1

    final override def valuesIterator: Iterator[HalfWordDescriptor[_]] =
      Iterator.single(v1)

    final override def nonEmpty =
      true

    final override def contains[A](ref: MemoryLocation[A]) =
      (ref eq v1.address)

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      if (k eq v1.address) {
        new LogMap1(v)
      } else {
        new LogMapArray(v1, v)
      }
    }

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) =
      if (k eq v1.address) v1.cast[A] else default

    final override def equals(that: Any): Boolean = that match {
      case that: LogMap1 =>
        this.v1 eq that.v1
      case _ =>
        false
    }

    final override def hashCode: Int =
      MurmurHash3.finalizeHash(v1.##, 1)
  }

  private[this] final val MaxArraySize =
    4 // TODO: determine size

  /** Invariant: has more than 1, and at most `MaxArraySize` items */
  private final class LogMapArray(
    private val array: Array[HalfWordDescriptor[Any]],
    private[this] val ord: MemoryLocationOrdering[Any],
  ) extends LogMap {

    require((array.length > 1) && (array.length <= MaxArraySize))

    def this(v1: HalfWordDescriptor[_], v2: HalfWordDescriptor[_]) = {
      this(
        {
          require(v1.address ne v2.address)
          val ord = MemoryLocation.memoryLocationOrdering
          val v1Any = v1.asInstanceOf[HalfWordDescriptor[Any]]
          val v2Any = v2.asInstanceOf[HalfWordDescriptor[Any]]
          val arr = new Array[HalfWordDescriptor[Any]](2)
          if (ord.lt(v1Any.address, v2Any.address)) { // v1 < v2
            arr(0) = v1Any
            arr(1) = v2Any
          } else { // v1 > v2
            assert(ord.gt(v1Any.address, v2Any.address))
            arr(0) = v2Any
            arr(1) = v1Any
          }
          arr
        },
        MemoryLocation.memoryLocationOrdering,
      )
    }

    final override def size =
      array.length

    final override def valuesIterator: Iterator[HalfWordDescriptor[_]] =
      array.iterator

    final override def nonEmpty =
      true

    final override def contains[A](ref: MemoryLocation[A]) =
      binSearch(ref) >= 0

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(v.address eq k)
      val idx = binSearch(k)
      if (idx >= 0) {
        // overwrite
        val newArr = Arrays.copyOf(this.array, this.array.length)
        newArr(idx) = v.cast[Any]
        new LogMapArray(newArr, ord)
      } else {
        // insert
        val newLength = this.array.length + 1
        if (newLength <= MaxArraySize) {
          val insertIdx = -(idx + 1)
          val newArr = new Array[HalfWordDescriptor[Any]](this.array.length + 1)
          System.arraycopy(this.array, 0, newArr, 0, insertIdx)
          newArr(insertIdx) = v.cast[Any]
          val remaining = (newArr.length - 1) - insertIdx
          if (remaining > 0) {
            System.arraycopy(this.array, insertIdx, newArr, insertIdx + 1, remaining)
          }
          new LogMapArray(newArr, ord)
        } else {
          new LogMapTree(this.array, extra = v.cast[Any])
        }
      }
    }

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) = {
      val idx = binSearch(k)
      if (idx >= 0) array(idx).cast[A]
      else default
    }

    final override def equals(that: Any): Boolean = that match {
      case that: LogMapArray =>
        (this eq that) || {
          (this.array.length == that.array.length) && {
            def go(idx: Int): Boolean = {
              if (idx == this.array.length) {
                true
              } else if (this.array(idx) == that.array(idx)) {
                go(idx + 1)
              } else {
                false
              }
            }
            go(0)
          }
        }
      case _ =>
        false
    }

    final override def hashCode: Int =
      MurmurHash3.arrayHash(this.array)

    private[this] final def binSearch[A](loc: MemoryLocation[A]): Int = {
      val ref = loc.cast[Any]
      def go(l: Int, r: Int): Int = {
        if (l <= r) {
          val m = (l + r) >>> 1
          ord.compare(array(m).address, ref) match {
            case o if o < 0 => // <
              go(l = m + 1, r = r)
            case o if o > 0 => // >
              go(l = l, r = m - 1)
            case _ => // =
              m
          }
        } else {
          // return insertion index:
          -(l + 1)
        }
      }
      go(l = 0, r = array.length - 1)
    }
  }

  /** Invariant: `treeMap` has more items than `MaxArraySize` */
  private final class LogMapTree(private val treeMap: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]])
    extends LogMap {

    require(treeMap.size > MaxArraySize)

    def this(arr: Array[HalfWordDescriptor[Any]], extra: HalfWordDescriptor[Any]) = {
      this({
        val b = TreeMap.newBuilder[MemoryLocation[Any], HalfWordDescriptor[Any]](
          MemoryLocation.memoryLocationOrdering
        )
        var idx = 0
        while (idx < arr.length) {
          val hwd = arr(idx)
          b += ((hwd.address, hwd))
          idx += 1
        }
        b += ((extra.address, extra))
        b.result()
      })
    }

    final override def size =
      treeMap.size

    final override def valuesIterator: Iterator[HalfWordDescriptor[_]] =
      treeMap.valuesIterator

    final override def nonEmpty =
      true

    final override def contains[A](ref: MemoryLocation[A]) =
      treeMap.contains(ref.cast[Any])

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap =
      new LogMapTree(treeMap.updated(k.cast[Any], v.cast[Any]))

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) =
      treeMap.getOrElse(k.cast[Any], default).asInstanceOf[HalfWordDescriptor[A]]

    final override def equals(that: Any): Boolean = that match {
      case that: LogMapTree =>
        this.treeMap.equals(that.treeMap)
      case _ =>
        false
    }

    final override def hashCode: Int =
      treeMap.##
  }
}
