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
 * HAMTs are described in "Ideal Hash Trees" by Phil Bagwell
 * (https://infoscience.epfl.ch/record/64398/files/idealhashtrees.pdf).
 * An improved encoding called CHAMP (compressed hash array mapped
 * prefix trie) is described in "Optimizing Hash-Array Mapped Tries for
 * Fast and Lean Immutable JVM Collections" by Michael J. Steindorfer
 * and Jurgen J. Vinju (https://ir.cwi.nl/pub/24029/24029B.pdf).
 * (TODO: Currently we don't use CHAMP here, because we need an iteration
 * order that is globally consistent, and it's not clear how to do
 * that in a CHAMP.)
 *
 * Unlike most HAMTs, we use 64-bit hashes in a 64-ary tree. (We can
 * do this, because keys eventually will be `Ref` IDs, which are `Long`s.)
 * We're using the IDs directly, without hashing, since they're already
 * generated with Fibonacci hashing. We don't store the keys separately,
 * since we can always get them from the values (which will be HWDs).
 * Values can't be `null` (we use `null` as the "not found" sentinel).
 * Deletion is not implemented, because we don't need it. We also don't
 * implement collision nodes, because `Ref` IDs are globally unique,
 * so it's not possible to have a collision on every level or the tree.
 *
 * This class contains the generic HAMT implementation. Abstract methods
 * are provided for the "MCAS-specific" things. The API is weird, because
 * we need this separation, but really don't want to allocate unnecessary
 * objects. (Overall, this is not really a general HAMT, although it's
 * somewhat abstracted away from the MCAS details.) For the MCAS-specific
 * parts, see `LogMap2`.
 *
 * Type parameters are as follows:
 * `K` is the type of keys.
 * `V` is the type of values in the map (i.e., HWDs; keys/hashes are `Long`s).
 * `E` is they type `toArray` converts the values to (`emcas.WordDescriptor[A]`
 * on the JVM, and equals to `A` on JS).
 * `T1` is the type of the "extra" value passed to `toArray`, which it just
 * passes on to `convertForArray` (this is the parent `EmcasDescriptor`).
 * `T2` is similarly the type of the "extra" value passed to `forAll`, which
 * it just passes on to `predicateForForAll` (an `Mcas.ThreadContext` to
 * implement revalidation).
 * `H` is the self-type (we use F-bounded polymorphism here, because we need
 * to create new nodes on insert/update, and `Hamt` is also the type of the
 * sub-nodes, not just the root).
 *
 * Public methods are the "external" API. We take care never to call them
 * on a node in lower levels (they assume they're called on the root).
 */
private[mcas] abstract class Hamt[K, V, E, T1, T2, H <: Hamt[K, V, E, T1, T2, H]] protected[mcas] (

  /**
   * The number of values in `this` subtree (i.e., if `this` is the
   * root, then this number is size of the whole tree).
   */
  final override val size: Int,

  /**
   * Contains 1 bits in exactly the places where the imaginary 64-element
   * sparse array has "something" (either a value or a sub-node).
   */
  private val bitmap: Long,

  /**
   * The dense array containing the values and/or sub-nodes. At most
   * 64-element long, but shorter for a "not full" node. Can be a
   * zero-element array (only for the root node of an empty tree).
   */
  private val contents: Array[AnyRef],
) extends AbstractHamt[K, V, E, T1, T2, H] { this: H =>

  /**
   * The highest 6 bits set; we start masking
   * the hash with this, and go to lower bits
   * as we go down in the tree.
   *
   * We use the highest 6 bits first, and
   * lower ones later, because for a
   * multiplicative hash (like the Fibonacci
   * we're using to generate IDs) the low
   * bits are the "worst quality" ones (see
   * https://stackoverflow.com/a/11872511).
   */
  private[this] final val START_MASK = 0xFC00000000000000L

  /** 6 bits for indexing a 64-element array */
  private[this] final val W = 6

  private[this] final val OP_UPDATE = 0
  private[this] final val OP_INSERT = 1
  private[this] final val OP_UPSERT = 2

  protected def newNode(size: Int, bitmap: Long, contents: Array[AnyRef]): H

  protected final override def contentsArr: Array[AnyRef] =
    this.contents

  protected final override def insertInternal(v: V): H =
    this.inserted(v)

  // API (should only be called on a root node!):

  final def nonEmpty: Boolean = {
    this.size > 0
  }

  final def getOrElseNull(hash: Long): V = {
    this.lookupOrNull(hash, 0)
  }

  /** Must already contain the key of `a` */
  final def updated(a: V): H = {
    this.insertOrOverwrite(hashOf(keyOf(a)), a, 0, OP_UPDATE) match {
      case null => this
      case newRoot => newRoot
    }
  }

  /** Mustn't already contain the key of `a` */
  final def inserted(a: V): H = {
    val newRoot = this.insertOrOverwrite(hashOf(keyOf(a)), a, 0, OP_INSERT)
    assert(newRoot ne null)
    newRoot
  }

  final def insertedAllFrom(that: H): H = {
    that.insertIntoHamt(this)
  }

  /** May or may not already contain the key of `a` */
  final def upserted(a: V): H = {
    this.insertOrOverwrite(hashOf(keyOf(a)), a, 0, OP_UPSERT) match {
      case null => this
      case newRoot => newRoot
    }
  }

  final def computeIfAbsent[T](k: K, tok: T, visitor: Hamt.EntryVisitor[K, V, T]): H = {
    this.visit(k, hashOf(k), tok, visitor, modify = false, shift = 0) match {
      case null =>
        this
      case newRoot =>
        newRoot
    }
  }

  final def computeOrModify[T](k: K, tok: T, visitor: Hamt.EntryVisitor[K, V, T]): H = {
    this.visit(k, hashOf(k), tok, visitor, modify = true, shift = 0) match {
      case null =>
        this
      case newRoot =>
        newRoot
    }
  }

  /**
   * Converts all values with `convertForArray`
   * (implemented in a subclass), and copies the
   * results into an array (created with `newArray`,
   * also implemented in a subclass).
   */
  final def toArray(tok: T1, flag: Boolean): Array[E] =
    this.copyToArrayInternal(tok, flag)

  final override def equals(that: Any): Boolean = {
    if (equ(this, that)) {
      true
    } else {
      that match {
        case that: Hamt[_, _, _, _, _, _] =>
          this.equalsInternal(that)
        case _ =>
          false
      }
    }
  }

  final override def hashCode: Int = {
    this.hashCodeInternal(0xf9ee8a53)
  }

  final override def toString: String = {
    this.toString(pre = "Hamt(", post = ")")
  }

  // Internal:

  // @tailrec
  private final def lookupOrNull(hash: Long, shift: Int): V = {
    this.getValueOrNodeOrNull(hash, shift) match {
      case null =>
        nullOf[V]
      case node: Hamt[_, _, _, _, _, _] =>
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
    val bitmap = this.bitmap
    if (bitmap != 0L) {
      val flag: Long = 1L << logicalIdx(hash, shift) // only 1 bit set, at the position in bitmap
      if ((bitmap & flag) != 0L) {
        // we have an entry for this:
        val idx: Int = physicalIdx(bitmap, flag)
        this.contents(idx)
      } else {
        // no entry for this hash:
        null
      }
    } else {
      // empty HAMT
      null
    }
  }

  private final def visit[T](k: K, hash: Long, tok: T, visitor: Hamt.EntryVisitor[K, V, T], modify: Boolean, shift: Int): H = {
    this.getValueOrNodeOrNull(hash, shift) match {
      case null =>
        visitor.entryAbsent(k, tok) match {
          case null =>
            nullOf[H]
          case newVal =>
            assert(hashOf(keyOf(newVal)) == hash)
            // TODO: this will compute physIdx again:
            this.insertOrOverwrite(hash, newVal, shift, op = OP_INSERT)
        }
      case node: Hamt[_, _, _, _, _, _] =>
        node.asInstanceOf[H].visit(k, hash, tok, visitor, modify = modify, shift = shift + W) match {
          case null =>
            nullOf[H]
          case newNode =>
            val newSize = this.size + (newNode.size - node.size)
            assert((modify && ((newSize == this.size) || (newSize == (this.size + 1)))) || (newSize == (this.size + 1)))
            val bitmap = this.bitmap
            // TODO: we're computing physIdx twice:
            val physIdx: Int = physicalIdx(bitmap, 1L << logicalIdx(hash, shift))
            this.withNode(newSize, bitmap, newNode, physIdx)
        }
      case value =>
        val a = value.asInstanceOf[V]
        val hashA = hashOf(keyOf(a))
        if (hash == hashA) {
          val newEntry = visitor.entryPresent(k, a, tok)
          if (modify) {
            if (equ(newEntry, a)) {
              nullOf[H]
            } else {
              assert(hashOf(keyOf(newEntry)) == hashA)
              this.insertOrOverwrite(hashA, newEntry, shift, op = OP_UPDATE)
            }
          } else {
            assert(equ(newEntry, a))
            nullOf[H]
          }
        } else {
          visitor.entryAbsent(k, tok) match {
            case null =>
              nullOf[H]
            case newVal =>
              assert(hashOf(keyOf(newVal)) == hash)
              // TODO: this will compute physIdx again:
              this.insertOrOverwrite(hash, newVal, shift, op = OP_INSERT)
          }
        }
    }
  }

  private final def insertOrOverwrite(hash: Long, value: V, shift: Int, op: Int): H = {
    val flag: Long = 1L << logicalIdx(hash, shift) // only 1 bit set, at the position in bitmap
    val bitmap = this.bitmap
    if (bitmap != 0L) {
      val contents = this.contents
      val physIdx: Int = physicalIdx(bitmap, flag)
      if ((bitmap & flag) != 0L) {
        // we have an entry for this:
        contents(physIdx) match {
          case node: Hamt[_, _, _, _, _, _] =>
            node.asInstanceOf[H].insertOrOverwrite(hash, value, shift + W, op) match {
              case null =>
                nullOf[H]
              case newNode =>
                this.withNode(this.size + (newNode.size - node.size), bitmap, newNode, physIdx)
            }
          case ov =>
            val oh = hashOf(keyOf(ov.asInstanceOf[V]))
            if (hash == oh) {
              if (op == OP_INSERT) {
                throw new IllegalArgumentException
              } else if (equ(ov, value)) {
                nullOf[H]
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
          newArr(physIdx) = box(value)
          this.newNode(this.size + 1, newBitmap, newArr)
        }
      }
    } else {
      // empty node
      if (op == OP_UPDATE) {
        throw new IllegalArgumentException
      } else {
        val newArr = new Array[AnyRef](1)
        newArr(0) = box(value)
        this.newNode(1, flag, newArr)
      }
    }
  }

  protected override def equalsInternal(that: AbstractHamt[_, _, _, _, _, _]): Boolean = {
    if (this.bitmap == that.asInstanceOf[H].bitmap) {
      super.equalsInternal(that)
    } else {
      // fast path:
      false
    }
  }

  private[this] final def withValue(bitmap: Long, value: V, physIdx: Int): H = {
    this.newNode(this.size, bitmap, arrReplacedValue(this.contents, box(value), physIdx))
  }

  private[this] final def withNode(size: Int, bitmap: Long, node: Hamt[K, V, E, _, _, _], physIdx: Int): H = {
    this.newNode(size, bitmap, arrReplacedValue(this.contents, node, physIdx))
  }

  private[this] final def arrReplacedValue(arr: Array[AnyRef], value: AnyRef, idx: Int): Array[AnyRef] = {
    val newArr = Arrays.copyOf(arr, arr.length)
    newArr(idx) = value
    newArr
  }

  /** Index into the imaginary 64-element sparse array */
  private[this] final def logicalIdx(hash: Long, shift: Int): Int = {
    // Note: this logic is duplicated in `MemoryLocationOrdering`.
    val mask = START_MASK >>> shift // masks the bits we're interested in
    val sh = java.lang.Long.numberOfTrailingZeros(mask) // we'll shift the masked result
    // we do it this way, because at the end, when `shift` is 60,
    // we don't actually need to shift (i.e., `sh` will be 0),
    // because we just need the 4 lowest bits
    ((hash & mask) >>> sh).toInt
    // TODO: It it measurably slower this way, than
    // TODO: just using the lowest bits first (see
    // TODO: `ShiftBench`). We should check if it
    // TODO: really matters.
  }

  /** For testing only! */
  private[mcas] final def logicalIdx_public(hash: Long, shift: Int): Int = {
    this.logicalIdx(hash, shift)
  }

  /** Index into the actual dense array (`contents`) */
  private[this] final def physicalIdx(bitmap: Long, flag: Long): Int = {
    java.lang.Long.bitCount(bitmap & (flag - 1L))
  }
}

private[choam] object Hamt {

  trait EntryVisitor[K, V, T] {
    def entryPresent(k: K, v: V, tok: T): V
    def entryAbsent(k: K, tok: T): V
  }
}
