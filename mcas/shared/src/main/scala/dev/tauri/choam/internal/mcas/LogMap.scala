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
package internal
package mcas

import scala.collection.immutable.TreeMap
import scala.util.hashing.MurmurHash3

// TODO: Could we use a Bloom filter for fast detection
// TODO: of read-only status (which could help with
// TODO: `Rxn`s which "become" read-only)?

private sealed abstract class LogMap {
  def size: Int
  def valuesIterator: Iterator[HalfWordDescriptor[_]]
  def nonEmpty: Boolean
  def contains[A](ref: MemoryLocation[A]): Boolean
  def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap
  def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]): HalfWordDescriptor[A]
  def isDisjoint(that: LogMap): Boolean
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

    final def isDisjoint(that: LogMap): Boolean =
      true

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
      require(k eq v.address)
      if (k eq v1.address) {
        new LogMap1(v)
      } else {
        new LogMapTree(v1.cast[Any], v.cast[Any])
      }
    }

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) =
      if (k eq v1.address) v1.cast[A] else default

    final def isDisjoint(that: LogMap): Boolean =
      !that.contains(this.v1.address)

    final override def equals(that: Any): Boolean = that match {
      case that: LogMap1 =>
        this.v1 == that.v1
      case _ =>
        false
    }

    final override def hashCode: Int =
      MurmurHash3.finalizeHash(v1.##, 1)
  }

  /** Invariant: `treeMap` has more than 1 items */
  private final class LogMapTree(
    private val treeMap: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]],
    private val bloomFilterLeft: Long,
    private val bloomFilterRight: Long,
  ) extends LogMap {

    require(treeMap.size > 1)

    def this(v1: HalfWordDescriptor[Any], v2: HalfWordDescriptor[Any]) = {
      this(
        {
          val b = TreeMap.newBuilder[MemoryLocation[Any], HalfWordDescriptor[Any]](
            MemoryLocation.memoryLocationOrdering
          )
          b += ((v1.address, v1))
          b += ((v2.address, v2))
          b.result()
        },
        BloomFilter.insertLeft(BloomFilter.insertLeft(0L, v1.address), v2.address),
        BloomFilter.insertRight(BloomFilter.insertRight(0L, v1.address), v2.address),
      )
    }

    final override def size =
      treeMap.size

    final override def valuesIterator: Iterator[HalfWordDescriptor[_]] =
      treeMap.valuesIterator

    final override def nonEmpty =
      true

    private[this] final def containsUnopt[A](ref: MemoryLocation[A]) =
      treeMap.contains(ref.cast[Any])

    final override def contains[A](ref: MemoryLocation[A]) = {
      if (BloomFilter.definitelyNotContains(bloomFilterLeft, bloomFilterRight, ref)) {
        false
      } else {
        this.containsUnopt(ref)
      }
    }

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      new LogMapTree(
        treeMap.updated(k.cast[Any], v.cast[Any]),
        BloomFilter.insertLeft(bloomFilterLeft, k),
        BloomFilter.insertRight(bloomFilterRight, k)
      )
    }

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) = {
      if (BloomFilter.definitelyNotContains(bloomFilterLeft, bloomFilterRight, k)) {
        default
      } else {
        treeMap.getOrElse(k.cast[Any], default).asInstanceOf[HalfWordDescriptor[A]]
      }
    }

    final def isDisjoint(that: LogMap): Boolean = {
      that match {
        case Empty =>
          true
        case that: LogMap1 =>
          that.isDisjoint(this)
        case that: LogMapTree =>
          this.isDisjointTree(that)
      }
    }

    private[this] final def isDisjointTree(that: LogMapTree): Boolean = {
      if ((this.bloomFilterLeft & that.bloomFilterLeft) != 0) {
        if ((this.bloomFilterRight & that.bloomFilterRight) != 0) {
          val common = this.treeMap.keySet.intersect(that.treeMap.keySet)
          common.isEmpty
        } else {
          true
        }
      } else {
        true
      }
    }

    final override def equals(that: Any): Boolean = that match {
      case that: LogMapTree =>
        // Note: no need to compare the Bloom filter,
        // as it is a function of `treeMap`.
        this.treeMap.equals(that.treeMap)
      case _ =>
        false
    }

    final override def hashCode: Int = {
      // Note: no need to hash the Bloom filter,
      // as it is a function of `treeMap`.
      treeMap.##
    }
  }
}
