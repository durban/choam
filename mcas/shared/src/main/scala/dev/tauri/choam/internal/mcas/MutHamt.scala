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

/**
 * Mutable HAMT; not thread safe; `null` values are forbidden.
 */
private[mcas] abstract class MutHamt[K, V, E, T1, T2, H <: MutHamt[K, V, E, T1, T2, H]](
  // NB: the root doesn't have a logical idx, so we're abusing this field to store the tree size
  private var logIdx: Int,
  private var contents: Array[AnyRef],
) {

  require(contents.length > 0)

  private[this] final val START_MASK = 0xFC00000000000000L

  private[this] final val W = 6

  private[this] final val OP_UPDATE = 0
  private[this] final val OP_INSERT = 1
  private[this] final val OP_UPSERT = 2

  protected def keyOf(a: V): K

  protected def hashOf(k: K): Long

  protected def newNode(logIdx: Int, contents: Array[AnyRef]): H

  protected def newArray(size: Int): Array[E]

  protected def convertForArray(a: V, tok: T1, flag: Boolean): E

  protected def predicateForForAll(a: V, tok: T2): Boolean

  // API (should only be called on a root node!):

  final def size: Int = {
    // we abuse the `logIdx` of the root to store the size of the whole tree:
    this.logIdx
  }

  final def nonEmpty: Boolean = {
    this.size > 0
  }

  final def getOrElseNull(hash: Long): V = {
    this.lookupOrNull(hash, 0)
  }

  final def update(a: V): Unit = {
    val sizeDiff = this.insertOrOverwrite(hashOf(keyOf(a)), a, shift = 0, op = OP_UPDATE)
    assert(sizeDiff == 0)
  }

  final def insert(a: V): Unit = {
    val sizeDiff = this.insertOrOverwrite(hashOf(keyOf(a)), a, shift = 0, op = OP_INSERT)
    assert(sizeDiff == 1)
    this.logIdx += 1
  }

  // TODO: insertAllFrom

  final def upsert(a: V): Unit = {
    val sizeDiff = this.insertOrOverwrite(hashOf(keyOf(a)), a, shift = 0, op = OP_UPSERT)
    assert((sizeDiff == 0) || (sizeDiff == 1))
    this.logIdx += sizeDiff
  }

  // TODO: computeIfAbsent/computeOrModify

  final def copyToArray(tok: T1, flag: Boolean): Array[E] = {
    val arr = this.newArray(this.size)
    val end = this.copyIntoArray(arr, 0, tok, flag = flag)
    assert(end == arr.length)
    arr
  }

  final def forAll(tok: T2): Boolean = {
    this.forAllInternal(tok)
  }

  // TODO: equals/hashCode/toString

  // Internal:

  private final def lookupOrNull(hash: Long, shift: Int): V = {
    this.getValueOrNodeOrNull(hash, shift) match {
      case null =>
        nullOf[V]
      case node: MutHamt[_, _, _, _, _, _] =>
        node.lookupOrNull(hash, shift + W).asInstanceOf[V]
      case value =>
        val a = value.asInstanceOf[V]
        val hashA = hashOf(keyOf(a))
        if (hash == hashA) {
          a
        } else {
          nullOf[V]
        }
    }
  }

  private[this] final def getValueOrNodeOrNull(hash: Long, shift: Int): AnyRef = {
    val contents = this.contents
    val logIdx = logicalIdx(hash, shift)
    val size = contents.length // always a power of 2
    val physIdx = physicalIdx(logIdx, size)
    contents(physIdx)
  }

  private[this] final def physicalIdx(logIdx: Int, size: Int): Int = {
    assert(logIdx < 64)
    // size is always a power of 2
    val width = java.lang.Integer.numberOfTrailingZeros(size)
    assert(width <= W)
    val sh = W - width
    logIdx >>> sh
  }

  private[mcas] final def physicalIdx_public(logIdx: Int, size: Int): Int = {
    physicalIdx(logIdx, size)
  }

  /** Returns the increase in size */
  private final def insertOrOverwrite(hash: Long, value: V, shift: Int, op: Int, tries: Int = 0): Int = {
    val contents = this.contents
    val logIdx = logicalIdx(hash, shift)
    val size = contents.length // always a power of 2
    val physIdx = physicalIdx(logIdx, size)
    contents(physIdx) match {
      case null =>
        if (op == OP_UPDATE) {
          throw new IllegalArgumentException
        } else {
          contents(physIdx) = box(value)
          1
        }
      case node: MutHamt[_, _, _, _, _, _] =>
        val nodeLogIdx = node.logIdx
        if (logIdx == nodeLogIdx) {
          node.asInstanceOf[H].insertOrOverwrite(hash, value, shift + W, op)
        } else {
          // growing mutates the tree, so we must check
          // for error conditions BEFORE doing it:
          if (op == OP_UPDATE) {
            // there is no chance that we'll be able to
            // update (even after growing):
            throw new IllegalArgumentException
          } else {
            // we need to grow this level:
            this.growLevel(newSize = necessarySize(logIdx, nodeLogIdx), shift = shift)
            // now we'll suceed for sure:
            this.insertOrOverwrite(hash, value, shift, op, tries = tries + 1)
          }
        }
      case ov =>
        val oh = hashOf(keyOf(ov.asInstanceOf[V]))
        if (hash == oh) {
          if (op == OP_INSERT) {
            throw new IllegalArgumentException
          } else if (!equ(ov, value)) {
            contents(physIdx) = box(value)
            0
          } else {
            0
          }
        } else {
          val oLogIdx = logicalIdx(oh, shift)
          if (logIdx == oLogIdx) {
            // hash collision at this level,
            // so we go down 1 level
            val childNode = {
              val cArr = new Array[AnyRef](2) // NB: 2 instead of 1
              cArr(physicalIdx(logicalIdx(oh, shift + W), size = 2)) = ov
              this.newNode(logIdx, cArr)
            }
            val r = childNode.insertOrOverwrite(hash, value, shift = shift + W, op = op)
            contents(physIdx) = childNode
            assert(r == 1)
            1
          } else {
            if (op == OP_UPDATE) {
              // there is no chance that we'll be able to
              // update (even after growing):
              throw new IllegalArgumentException
            } else {
              // grow this level:
              this.growLevel(newSize = necessarySize(logIdx, oLogIdx), shift = shift)
              // now we'll suceed for sure:
              this.insertOrOverwrite(hash, value, shift, op, tries = tries + 1)
            }
          }
        }
    }
  }

  private final def copyIntoArray(arr: Array[E], start: Int, tok: T1, flag: Boolean): Int = {
    val contents = this.contents
    var i = 0
    var arrIdx = start
    val len = contents.length
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: MutHamt[_, _, _, _, _, _] =>
          arrIdx = node.asInstanceOf[H].copyIntoArray(arr, arrIdx, tok, flag = flag)
        case a =>
          arr(arrIdx) = convertForArray(a.asInstanceOf[V], tok, flag = flag)
          arrIdx += 1
      }
      i += 1
    }
    arrIdx
  }

  private[this] final def growLevel(newSize: Int, shift: Int): Unit = {
    assert((newSize & (newSize - 1)) == 0) // power of 2
    val newContents = new Array[AnyRef](newSize)
    val contents = this.contents
    val size = contents.length
    assert(newSize > size)
    var idx = 0
    while (idx < size) {
      contents(idx) match {
        case null =>
          ()
        case node: MutHamt[_, _, _, _, _, _] =>
          val logIdx = node.logIdx
          val newPhysIdx = physicalIdx(logIdx, newSize)
          newContents(newPhysIdx) = node
        case value =>
          val logIdx = logicalIdx(hashOf(keyOf(value.asInstanceOf[V])), shift)
          val newPhysIdx = physicalIdx(logIdx, newSize)
          newContents(newPhysIdx) = value
      }
      idx += 1
    }
    this.contents = newContents
  }

  private final def forAllInternal(tok: T2): Boolean = {
    val contents = this.contents
    var i = 0
    val len = contents.length
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: MutHamt[_, _, _, _, _, _] =>
          if (!node.asInstanceOf[H].forAllInternal(tok)) {
            return false // scalafix:ok
          }
        case a =>
          if (!this.predicateForForAll(a.asInstanceOf[V], tok)) {
            return false // scalafix:ok
          }
      }
      i += 1
    }

    true
  }

  private[this] final def necessarySize(logIdx1: Int, logIdx2: Int): Int = {
    assert(logIdx1 != logIdx2)
    val diff = logIdx1 ^ logIdx2
    val necessaryBits = java.lang.Integer.numberOfLeadingZeros(diff) - (32 - W) + 1
    assert(necessaryBits <= W)
    1 << necessaryBits
  }

  private[mcas] final def necessarySize_public(logIdx1: Int, logIdx2: Int): Int = {
    this.necessarySize(logIdx1, logIdx2)
  }

  /** Index into the imaginary 64-element sparse array */
  private[this] final def logicalIdx(hash: Long, shift: Int): Int = {
    val mask = START_MASK >>> shift // masks the bits we're interested in
    val sh = java.lang.Long.numberOfTrailingZeros(mask) // we'll shift the masked result
    ((hash & mask) >>> sh).toInt
  }
}
