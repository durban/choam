/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
private[mcas] abstract class Hamt[K <: Hamt.HasHash, V <: Hamt.HasKey[K], E <: AnyRef, T1, T2, H <: Hamt[K, V, E, T1, T2, H]] protected[mcas] (

  private val sizeAndBlue: Int,

  /*
   * Contains 1 bits in exactly the places where the imaginary 64-element
   * sparse array has "something" (either a value, a sub-node, or a tombstone).
   */
  private val bitmap: Long,

  /*
   * The dense array containing the values and/or sub-nodes. At most
   * 64-element long, but shorter for a "not full" node. Can be a
   * zero-element array (only for the root node of an empty tree).
   */
  private val contents: Array[AnyRef],
) extends AbstractHamt[K, V, E, T1, T2, H] { this: H =>

  /** 6 bits for indexing a 64-element array */
  private[this] final val W = 6

  private[this] final val OP_UPDATE = 0
  private[this] final val OP_INSERT = 1
  private[this] final val OP_UPSERT = 2

  protected def newNode(sizeAndBlue: Int, bitmap: Long, contents: Array[AnyRef]): H

  protected final override def contentsArr: Array[AnyRef] =
    this.contents

  protected final override def insertInternal(v: V): H =
    this.inserted(v)

  private[mcas] final def isBlueSubtree: Boolean = {
    this.sizeAndBlue >= 0
  }

  // API (should only be called on a root node!):

  /**
   * The number of values in `this` subtree (i.e., if `this` is the
   * root, then this number is size of the whole tree).
   */
  final override def size: Int = {
    java.lang.Math.abs(this.sizeAndBlue)
  }

  final def nonEmpty: Boolean = {
    this.size > 0
  }

  final def getOrElseNull(hash: Long): V = {
    this.lookupOrNull(hash, 0)
  }

  /** Must already contain the key of `a` */
  final def updated(a: V): H = {
    this.insertOrOverwrite(a.key.hash, a, 0, OP_UPDATE) match {
      case null => this
      case newRoot => newRoot
    }
  }

  /** Mustn't already contain the key of `a` */
  final def inserted(a: V): H = {
    val newRoot = this.insertOrOverwrite(a.key.hash, a, 0, OP_INSERT)
    _assert(newRoot ne null)
    newRoot
  }

  final def insertedAllFrom(that: H): H = {
    that.insertIntoHamt(this)
  }

  /** May or may not already contain the key of `a` */
  final def upserted(a: V): H = {
    this.insertOrOverwrite(a.key.hash, a, 0, OP_UPSERT) match {
      case null => this
      case newRoot => newRoot
    }
  }

  final def computeIfAbsent[T](k: K, tok: T, visitor: Hamt.EntryVisitor[K, V, T]): H = {
    this.visit(k, k.hash, tok, visitor, modify = false, shift = 0) match {
      case null => this
      case newRoot => newRoot
    }
  }

  final def computeOrModify[T](k: K, tok: T, visitor: Hamt.EntryVisitor[K, V, T]): H = {
    this.visit(k, k.hash, tok, visitor, modify = true, shift = 0) match {
      case null => this
      case newRoot => newRoot
    }
  }

  final def removed(k: K): H = {
    this.computeOrModify(k, null, Hamt.tombingVisitor[K, V])
  }

  @throws[Hamt.IllegalRemovalException]("if the value is not blue")
  final def withoutBlueValue(k: K): H = {
    this.computeOrModify(k, this, AbstractHamt.tombingIfBlueVisitor[K, V, H])
  }

  /**
   * Converts all values with `convertForArray`
   * (implemented in a subclass), and copies the
   * results into an array (created with `newArray`,
   * also implemented in a subclass).
   */
  final def toArray(tok: T1, flag: Boolean, nullIfBlue: Boolean): Array[E] =
    this.copyToArrayInternal(tok, flag = flag, nullIfBlue = nullIfBlue)

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
    this.getValueOrNodeOrNullOrTombstone(hash, shift) match {
      case null =>
        nullOf[V]
      case node: Hamt[_, _, _, _, _, _] =>
        node.lookupOrNull(hash, shift + W).asInstanceOf[V]
      case value =>
        val a = value.asInstanceOf[V]
        if ((!a.isTomb) && (hash == a.key.hash)) {
          a
        } else {
          nullOf[V]
        }
    }
  }

  private[this] final def getValueOrNodeOrNullOrTombstone(hash: Long, shift: Int): AnyRef = {
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
    this.getValueOrNodeOrNullOrTombstone(hash, shift) match {
      case null =>
        this.visitEntryAbsent(k, hash, tok, visitor, shift = shift)
      case node: Hamt[_, _, _, _, _, _] =>
        node.asInstanceOf[H].visit(k, hash, tok, visitor, modify = modify, shift = shift + W) match {
          case null =>
            nullOf[H]
          case newNode =>
            val oldSize = this.size
            val newSize = oldSize + (newNode.size - node.size)
            _assert((modify && ((newSize >= (oldSize - 1)) && (newSize <= (oldSize + 1)))) || (newSize == (oldSize + 1)))
            val bitmap = this.bitmap
            // TODO: we're computing physIdx twice:
            val physIdx: Int = physicalIdx(bitmap, 1L << logicalIdx(hash, shift))
            this.withNode(newSize, bitmap, newNode, physIdx)
        }
      case value =>
        val a = value.asInstanceOf[V]
        val hashA = a.key.hash
        if (hash == hashA) {
          if (!a.isTomb) {
            val newEntry = visitor.entryPresent(k, a, tok)
            if (modify) {
              if (equ(newEntry, a)) {
                nullOf[H]
              } else {
                _assert(newEntry.key.hash == hashA)
                this.insertOrOverwrite(hashA, newEntry, shift, op = OP_UPDATE)
              }
            } else {
              _assert(equ(newEntry, a))
              nullOf[H]
            }
          } else {
            this.visitEntryAbsent(k, hash, tok, visitor, shift = shift)
          }
        } else {
          this.visitEntryAbsent(k, hash, tok, visitor, shift = shift)
        }
    }
  }

  private[this] final def visitEntryAbsent[T](k: K, hash: Long, tok: T, visitor: Hamt.EntryVisitor[K, V, T], shift: Int): H = {
    visitor.entryAbsent(k, tok) match {
      case null =>
        nullOf[H]
      case newVal =>
        _assert(newVal.key.hash == hash)
        // TODO: this will compute physIdx again:
        this.insertOrOverwrite(hash, newVal, shift, op = OP_INSERT)
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
          case ov => // or Tombstone
            val ovv = ov.asInstanceOf[V]
            val oh = ovv.key.hash
            if (hash == oh) {
              if (ovv.isTomb) {
                if (op == OP_UPDATE) {
                  throwIllegalUpdate(value)
                }
              } else {
                if (op == OP_INSERT) {
                  throwIllegalInsert(value)
                }
              }
              // ok, checked for errors, now do the thing:
              if (equ(ovv, value)) {
                nullOf[H] // nothing to do
              } else {
                this.withValue(bitmap, oldValue = ovv, newValue = value, physIdx = physIdx)
              }
            } else {
              // hash collision at this level,
              // so we go down one level:
              val childNode1Size = if (ovv.isTomb) 0 else 1
              val childNode1 = {
                val cArr = new Array[AnyRef](1)
                cArr(0) = ovv
                val oFlag = 1L << logicalIdx(oh, shift + W)
                this.newNode(sizeAndBlue = packSizeAndBlueInternal(childNode1Size, isBlueOrTomb(ovv)), bitmap = oFlag, contents = cArr)
              }
              val childNode2 = childNode1.insertOrOverwrite(hash, value, shift + W, op)
              this.withNode(this.size + (childNode2.size - childNode1Size), bitmap, childNode2, physIdx)
            }
        }
      } else {
        // no entry for this hash:
        if (op == OP_UPDATE) {
          throwIllegalUpdate(value)
        } else {
          val newBitmap: Long = bitmap | flag
          val len = contents.length
          val newArr = new Array[AnyRef](len + 1)
          System.arraycopy(contents, 0, newArr, 0, physIdx)
          newArr(physIdx) = value
          System.arraycopy(contents, physIdx, newArr, physIdx + 1, len - physIdx)
          _assert(!value.isTomb)
          this.newNode(
            sizeAndBlue = packSizeAndBlueInternal(this.size + 1, this.isBlueSubtree && isBlue(value)),
            bitmap = newBitmap,
            contents = newArr
          )
        }
      }
    } else {
      // empty node
      if (op == OP_UPDATE) {
        throwIllegalUpdate(value)
      } else {
        val newArr = new Array[AnyRef](1)
        newArr(0) = value
        _assert(!value.isTomb)
        this.newNode(packSizeAndBlueInternal(1, isBlue(value)), flag, newArr)
      }
    }
  }

  private[this] final def throwIllegalInsert(value: V): Nothing = {
    throw new Hamt.IllegalInsertException(value.key)
  }

  private[this] final def throwIllegalUpdate(value: V): Nothing = {
    throw new Hamt.IllegalUpdateException(value.key)
  }

  private[this] final def isBlueOrTomb(value: V): Boolean = {
    value.isTomb || this.isBlue(value)
  }

  private[this] final def withValue(bitmap: Long, oldValue: V, newValue: V, physIdx: Int): H = {
    val sizeDiff = if (newValue.isTomb) {
      // NB: we never overwrite a tombstone with a tombstone
      _assert(!oldValue.isTomb)
      -1
    } else if (oldValue.isTomb) {
      1
    } else {
      0
    }
    this.newNode(
      sizeAndBlue = packSizeAndBlueInternal(this.size + sizeDiff, this.isBlueSubtree && isBlueOrTomb(newValue)),
      bitmap = bitmap,
      contents = arrReplacedValue(this.contents, newValue, physIdx),
    )
  }

  private[this] final def withNode(size: Int, bitmap: Long, node: Hamt[K, V, E, _, _, _], physIdx: Int): H = {
    this.newNode(
      sizeAndBlue = packSizeAndBlueInternal(size, this.isBlueSubtree && node.isBlueSubtree),
      bitmap = bitmap,
      contents = arrReplacedValue(this.contents, node, physIdx),
    )
  }

  private[this] final def arrReplacedValue(arr: Array[AnyRef], value: AnyRef, idx: Int): Array[AnyRef] = {
    val newArr = Arrays.copyOf(arr, arr.length)
    newArr(idx) = value
    newArr
  }

  /** Index into the imaginary 64-element sparse array */
  private[this] final def logicalIdx(hash: Long, shift: Int): Int = {
    // Note: this logic is duplicated in `MutHamt` and `MemoryLocationOrdering`.
    // We use the highest 6 bits of the hash first (on the 1st level), and
    // lower ones later, because for a multiplicative hash (like the Fibonacci
    // we're using to generate IDs) the low bits are the "worst quality" ones
    // (see https://stackoverflow.com/a/11872511).
    // Note: `shift` is 0, 6, 12, ..., 60; at the last level (shift = 60)
    // this will cause the least significant 2 bits of `logicalIndex` to
    // always be 0 (i.e., it will be like `0bXXXX00`). But we account for
    // that when necessary (`MutHamt`). It's not worth making it like `0bXXXX`;
    // this way it's less complex and faster too.
    ((hash << shift) >>> 58).toInt
  }

  /** For testing only! */
  private[mcas] final def logicalIdx_public(hash: Long, shift: Int): Int = {
    this.logicalIdx(hash, shift)
  }

  /** Index into the actual dense array (`contents`) */
  private[this] final def physicalIdx(bitmap: Long, flag: Long): Int = {
    java.lang.Long.bitCount(bitmap & (flag - 1L))
  }

  // TODO: this is duplicated with `AbstractHamt`
  private[this] final def packSizeAndBlueInternal(size: Int, isBlue: Boolean): Int = {
    if (isBlue) size
    else -size
  }
}

private[choam] object Hamt {

  trait HasKey[K <: HasHash] {
    def key: K
    def isTomb: Boolean
  }

  trait HasHash {
    def hash: Long
  }

  trait EntryVisitor[K, V, T] {
    def entryPresent(k: K, v: V, tok: T): V
    def entryAbsent(k: K, tok: T): V
  }

  sealed abstract class IllegalOperationException(msg: String)
    extends IllegalArgumentException(msg) {

    final override def fillInStackTrace(): Throwable =
      this

    final override def initCause(cause: Throwable): Throwable =
      throw new IllegalStateException
  }

  final class IllegalInsertException private[mcas] (key: HasHash)
    extends IllegalOperationException(s"illegal INSERT operation for key: ${key}")

  final class IllegalUpdateException private[mcas] (key: HasHash)
    extends IllegalOperationException(s"illegal UPDATE operation for key: ${key}")

  final class IllegalRemovalException private[mcas] (key: HasHash)
    extends IllegalOperationException(s"illegal removal operation for key: ${key} (value is not blue)")

  private[this] final class Tombstone(final override val hash: Long)
    extends HasKey[HasHash]
    with HasHash {

    final override def key: HasHash =
      this

    final override def isTomb: Boolean =
      true
  }

  private[mcas] final def newTombstone(hash: Long): HasKey[HasHash] with HasHash =
    new Tombstone(hash)

  private[mcas] final def tombingVisitor[K <: HasHash, V <: HasKey[K]]: EntryVisitor[K, V, Any] = {
    _tombingVisitor.asInstanceOf[EntryVisitor[K, V, Any]]
  }

  private[this] val _tombingVisitor: EntryVisitor[HasHash, HasKey[HasHash], Any] = {
    new EntryVisitor[HasHash, HasKey[HasHash], Any] {
      final override def entryPresent(k: HasHash, v: HasKey[HasHash], tok: Any): HasKey[HasHash] =
        new Tombstone(k.hash)
      final override def entryAbsent(k: HasHash, tok: Any): HasKey[HasHash] =
        null // OK, nothing to delete
    }
  }
}
