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
package internal
package mcas

import scala.collection.immutable.LongMap
import scala.util.hashing.MurmurHash3

// TODO: Could we use a Bloom filter for fast detection
// TODO: of read-only status (which could help with
// TODO: `Rxn`s which "become" read-only)?

private sealed abstract class LogMap {

  def size: Int

  def valuesIterator: Iterator[HalfWordDescriptor[_]]

  def nonEmpty: Boolean

  /** Must already contain `k` */
  def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap

  /** Mustn't already contain `k` */
  def inserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap

  /** May or may not already contain `k` */
  def upserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap

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

    final override def valuesIterator: Iterator[HalfWordDescriptor[_]] =
      Iterator.empty

    final override def nonEmpty =
      false

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap =
      throw new IllegalArgumentException

    final override def inserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap =
      new LogMap1(v)

    final override def upserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap =
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

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(k eq v.address)
      require(k eq v1.address)
      new LogMap1(v)
    }

    final override def inserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(k eq v.address)
      require(k ne v1.address)
      new LogMapTree(v1.cast[Any], v.cast[Any])
    }

    final override def upserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(k eq v.address)
      if (k eq v1.address) {
        new LogMap1(v)
      } else {
        new LogMapTree(v1.cast[Any], v.cast[Any])
      }
    }

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) =
      if (k eq v1.address) v1.cast[A] else default

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
  private final class LogMapTree private (
    private val treeMap: LongMap[HalfWordDescriptor[Any]],
    final override val size: Int, // `LongMap` traverses the whole tree for `.size`
    // TODO: figure out if this Bloom filter is still useful:
    private val bloomFilterLeft: Long,
    private val bloomFilterRight: Long,
  ) extends LogMap {

    def this(v1: HalfWordDescriptor[Any], v2: HalfWordDescriptor[Any]) = {
      this(
        {
          require(v1 ne v2)
          LongMap
            .empty[HalfWordDescriptor[Any]]
            .updated(v1.address.id, v1)
            .updated(v2.address.id, v2)
        },
        2,
        BloomFilter.insertLeft(BloomFilter.insertLeft(0L, v1.address), v2.address),
        BloomFilter.insertRight(BloomFilter.insertRight(0L, v1.address), v2.address),
      )
    }

    final override def valuesIterator: Iterator[HalfWordDescriptor[_]] =
      treeMap.valuesIterator

    final override def nonEmpty =
      true

    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(k eq v.address)
      // TODO: this is a hack to detect if not already there:
      var wasPresent = false
      val newMap = treeMap.updateWith(k.id, v.cast[Any], { (_, nv) =>
        wasPresent = true
        nv
      })
      require(wasPresent)
      new LogMapTree(
        newMap,
        this.size,
        BloomFilter.insertLeft(bloomFilterLeft, k),
        BloomFilter.insertRight(bloomFilterRight, k)
      )
    }

    final override def inserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(k eq v.address)
      // TODO: this is a hack to detect if already there:
      var wasPresent = false
      val newMap = treeMap.updateWith(k.id, v.cast[Any], { (_, nv) =>
        wasPresent = true
        nv
      })
      require(!wasPresent)
      new LogMapTree(
        newMap,
        this.size + 1,
        BloomFilter.insertLeft(bloomFilterLeft, k),
        BloomFilter.insertRight(bloomFilterRight, k)
      )
    }

    final override def upserted[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap = {
      require(k eq v.address)
      // TODO: this is a hack to be able to maintain `size`:
      var wasPresent = false
      val newMap = treeMap.updateWith(k.id, v.cast[Any], { (_, nv) =>
        wasPresent = true
        nv
      })
      new LogMapTree(
        newMap,
        if (wasPresent) this.size else this.size + 1,
        BloomFilter.insertLeft(bloomFilterLeft, k),
        BloomFilter.insertRight(bloomFilterRight, k)
      )
    }

    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) = {
      if (BloomFilter.definitelyNotContains(bloomFilterLeft, bloomFilterRight, k)) {
        default
      } else {
        treeMap.getOrElse(k.id, default).asInstanceOf[HalfWordDescriptor[A]]
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
