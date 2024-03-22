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

import java.util.Arrays

/**
 * Immutable HAMT (hash array mapped trie)
 *
 * Keys are ref IDs (i.e., `Long`s), values are HWDs.
 * We're using the IDs directly, without hashing, since
 * they're already generated with Fibonacci hashing.
 * We don't store the keys separately, since we can
 * always get them from the values.
 *
 * Values can't be `null` (we use `null` as the "not
 * found" sentinel).
 */
private[mcas] abstract class Hamt[A, E, N <: Hamt.Node[A, E, N], H <: Hamt[A, E, N, H]](
  private val root: N, // TODO: do we need this extra indirection?
) { this: H =>

  protected def withRoot(root: N): H

  final def size: Int =
    this.root.size

  final def getOrElse(hash: Long, default: A): A = // TODO: we don't ever actually need `default`
    this.root.getOrElse(hash, default)

  final def updated(a: A): H =
    this.withRoot(this.root.updated(a))

  final def inserted(a: A): H =
    this.withRoot(this.root.inserted(a))

  final def upserted(a: A): H =
    this.withRoot(this.root.upserted(a))

  final def toArray: Array[E] =
    this.root.toArray
}

private[mcas] object Hamt {

  private[mcas] abstract class Node[A, E, N <: Node[A, E, N]](
    val size: Int,
    val bitmap: Long,
    val contents: Array[AnyRef],
  ) { this: N =>

    private[this] final val W =
      6

    protected def hashOf(a: A): Long // TODO: this is duplicated with `Hamt`

    protected def newNode(size: Int, bitmap: Long, contents: Array[AnyRef]): N

    protected def newArray(size: Int): Array[E]

    protected def convertForArray(a: A): E

    // API (should only be called on a root node):

    final def getOrElse(hash: Long, default: A): A = { // TODO: we don't ever actually need `default`
      this.lookupOrNull(hash, 0) match {
        case null => default
        case a => a
      }
    }

    final def updated(a: A): N = {
      this.insertOrOverwrite(hashOf(a), a, 0, OP_UPDATE) match {
        case null => this
        case newRoot => newRoot
      }
    }

    final def inserted(a: A): N = {
      val newRoot = this.insertOrOverwrite(hashOf(a), a, 0, OP_INSERT)
      assert(newRoot ne null)
      newRoot
    }

    final def upserted(a: A): N = {
      this.insertOrOverwrite(hashOf(a), a, 0, OP_UPSERT) match {
        case null => this
        case newRoot => newRoot
      }
    }

    final def toArray: Array[E] = {
      val arr = newArray(this.size)
      val end = this.copyIntoArray(arr, 0)
      assert(end == arr.length)
      arr
    }

    // Internal:

    // @tailrec
    final def lookupOrNull(hash: Long, shift: Int): A = {
      this.getValueOrNodeOrNull(hash, shift) match {
        case null =>
          nullOf[A]
        case node: Node[A, E, _] =>
          node.lookupOrNull(hash, shift + W)
        case value =>
          val a = value.asInstanceOf[A]
          val hashA = hashOf(a)
          if (hash == hashA) {
            a
          } else {
            nullOf[A]
          }
      }
    }

    private[this] final def getValueOrNodeOrNull(hash: Long, shift: Int): AnyRef = {
      val flag: Long = 1L << logicalIdx(hash, shift) // only 1 bit set, at the position in bitmap
      val bitmap = this.bitmap
      if ((bitmap & flag) != 0L) {
        // we have an entry for this:
        val idx: Int = physicalIdx(bitmap, flag)
        this.contents(idx)
      } else {
        // no entry for this hash:
        null
      }
    }

    // TODO: duplicated code
    private[this] final val OP_UPDATE = 0
    private[this] final val OP_INSERT = 1
    private[this] final val OP_UPSERT = 2

    final def insertOrOverwrite(hash: Long, value: A, shift: Int, op: Int): N = {
      val flag: Long = 1L << logicalIdx(hash, shift) // only 1 bit set, at the position in bitmap
      val bitmap = this.bitmap
      val contents = this.contents
      val physIdx: Int = physicalIdx(bitmap, flag)
      if ((bitmap & flag) != 0L) {
        // we have an entry for this:
        contents(physIdx) match {
          case node: Node[A, E, N] =>
            node.insertOrOverwrite(hash, value, shift + W, op) match {
              case null =>
                nullOf[N]
              case newNode: Node[A, E, N] =>
                this.withNode(this.size + (newNode.size - node.size), bitmap, newNode, physIdx)
            }
          case ov =>
            val oh = hashOf(ov.asInstanceOf[A])
            if (hash == oh) {
              if (op == OP_INSERT) {
                throw new IllegalArgumentException
              } else if (equ(ov, value)) {
                nullOf[N]
              } else {
                this.withValue(bitmap, value, physIdx)
              }
            } else {
              // hash collision at this level,
              // so we go down one level:
              val childNode = {
                val cArr = new Array[AnyRef](1)
                cArr(0) = ov
                val oFlag = 1L << logicalIdx(oh, shift + W)
                this.newNode(1, oFlag, cArr)
              }
              val childNode2 = childNode.insertOrOverwrite(hash, value, shift + W, op)
              this.withNode(this.size + (childNode2.size - 1), bitmap, childNode2, physIdx)
            }
        }
      } else {
        // no entry for this hash:
        if (op == OP_UPDATE) {
          throw new IllegalArgumentException
        } else {
          val newBitmap: Long = bitmap | flag
          val len = contents.length
          val newArr = new Array[AnyRef](len + 1)
          System.arraycopy(contents, 0, newArr, 0, physIdx)
          System.arraycopy(contents, physIdx, newArr, physIdx + 1, len - physIdx)
          newArr(physIdx) = value.asInstanceOf[AnyRef]
          this.newNode(this.size + 1, newBitmap, newArr)
        }
      }
    }

    final def copyIntoArray(arr: Array[E], start: Int): Int = {
      val contents = this.contents
      var i = 0
      var arrIdx = start
      val len = contents.length
      while (i < len) {
        contents(i) match {
          case node: Node[A, E, N] =>
            arrIdx = node.copyIntoArray(arr, arrIdx)
          case a =>
            arr(arrIdx) = convertForArray(a.asInstanceOf[A])
            arrIdx += 1
        }
        i += 1
      }
      arrIdx
    }

    private[this] final def withValue(bitmap: Long, value: A, physIdx: Int): N = {
      this.newNode(this.size, bitmap, arrReplacedValue(this.contents, box(value), physIdx))
    }

    private[this] final def withNode(size: Int, bitmap: Long, node: Node[A, E, _], physIdx: Int): N = {
      this.newNode(size, bitmap, arrReplacedValue(this.contents, node, physIdx))
    }

    private[this] final def arrReplacedValue(arr: Array[AnyRef], value: AnyRef, idx: Int): Array[AnyRef] = {
      val newArr = Arrays.copyOf(arr, arr.length)
      newArr(idx) = value
      newArr
    }

    /** Index into the imaginary 64-element sparse array */
    private[this] final def logicalIdx(hash: Long, shift: Int): Int = {
      // TODO: We should use the highest 6 bits first,
      // TODO: and lower ones later, because for a
      // TODO: multiplicative hash (like the Fibonacci
      // TODO: we're using to generate IDs) the low
      // TODO: bits are the "worst quality" ones
      // TODO: (see https://stackoverflow.com/a/11872511).
      // 63 is 0b111111
      ((hash >>> shift) & 63L).toInt
    }

    /** Index into the actual dense array (`contents`) */
    private[this] final def physicalIdx(bitmap: Long, flag: Long): Int = {
      java.lang.Long.bitCount(bitmap & (flag - 1L))
    }
  }
}
