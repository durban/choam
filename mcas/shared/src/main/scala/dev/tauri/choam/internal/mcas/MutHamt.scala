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

/**
 * Mutable HAMT; not thread safe; `null` values are forbidden.
 */
private[mcas] abstract class MutHamt[K <: Hamt.HasHash, V <: Hamt.HasKey[K], E <: AnyRef, T1, T2, I <: Hamt[K, V, E, T1, T2, I], H <: MutHamt[K, V, E, T1, T2, I, H]] protected[mcas] (
  // NB: the root doesn't have a logical idx, so we're abusing this field to store the tree size (and also the blue bit)
  private var logIdx: Int,
  private var contents: Array[AnyRef],
) extends AbstractHamt[K, V, E, T1, T2, H] { this: H =>

  // TODO: We're often recomputing things like physIdx
  // TODO: or logIdxWidth; figure out if we can be
  // TODO: faster by not repeating these computations
  // TODO: (by inlining methods if necessary).

  require(contents.length > 0)

  private[this] final val W = 6

  private[this] final val OP_UPDATE = 0
  private[this] final val OP_INSERT = 1
  private[this] final val OP_UPSERT = 2

  protected def newNode(logIdx: Int, contents: Array[AnyRef]): H

  protected def newImmutableNode(sizeAndBlue: Int, bitmap: Long, contents: Array[AnyRef]): I

  protected final override def contentsArr: Array[AnyRef] =
    this.contents

  protected final override def insertInternal(v: V): H = {
    this.insert(v)
    this
  }

  protected final def isBlueTree: Boolean = {
    this.logIdx < 0
  }

  private[this] final def isBlueTree_=(isBlue: Boolean): Unit = {
    val logIdx = this.logIdx
    val bit = if (isBlue) 0x80000000 else 0x0
    this.logIdx = (logIdx & 0x7fffffff) | bit
  }

  private[mcas] final def setIsBlueTree_public(isBlue: Boolean): Unit = {
    this.isBlueTree = isBlue
  }

  // API (should only be called on a root node!):

  final override def size: Int = {
    // we abuse the `logIdx` of the root to store the size of the whole tree:
    this.logIdx & 0x7fffffff
  }

  private[this] final def addToSize(s: Int): Unit = {
    val logIdx = this.logIdx
    val newSize = java.lang.Math.addExact(logIdx & 0x7fffffff, s)
    val bit = logIdx & 0x80000000
    this.logIdx = newSize | bit
  }

  private[mcas] final def addToSize_public(s: Int): Unit = {
    require(s >= 0)
    this.addToSize(s)
  }

  final def nonEmpty: Boolean = {
    this.size > 0
  }

  final def getOrElseNull(hash: Long): V = {
    this.lookupOrNull(hash, 0)
  }

  final def update(a: V): Unit = {
    val sdb = this.insertOrOverwrite(a.key.hash, a, shift = 0, op = OP_UPDATE)
    _assert(unpackSizeDiff(sdb) == 0)
    this.isBlueTree &= unpackIsBlue(sdb)
  }

  final def insert(a: V): Unit = {
    val sdb = this.insertOrOverwrite(a.key.hash, a, shift = 0, op = OP_INSERT)
    _assert(unpackSizeDiff(sdb) == 1)
    this.addToSize(1)
    this.isBlueTree &= unpackIsBlue(sdb)
  }

  final def insertAllFrom(that: H): H = {
    that.insertIntoHamt(this)
  }

  final def upsert(a: V): Unit = {
    val sdb = this.insertOrOverwrite(a.key.hash, a, shift = 0, op = OP_UPSERT)
    val sizeDiff = unpackSizeDiff(sdb)
    _assert((sizeDiff == 0) || (sizeDiff == 1))
    this.addToSize(sizeDiff)
    this.isBlueTree &= unpackIsBlue(sdb)
  }

  final def computeIfAbsent[T](k: K, tok: T, visitor: Hamt.EntryVisitor[K, V, T]): Unit = {
    val sdb = this.visit(k, k.hash, tok, visitor, newValue = nullOf[V], modify = false, shift = 0)
    val sizeDiff = unpackSizeDiff(sdb)
    _assert((sizeDiff == 0) || (sizeDiff == 1))
    this.addToSize(sizeDiff)
    this.isBlueTree &= unpackIsBlue(sdb)
  }

  final def computeOrModify[T](k: K, tok: T, visitor: Hamt.EntryVisitor[K, V, T]): Unit = {
    val sdb = this.visit(k, k.hash, tok, visitor, newValue = nullOf[V], modify = true, shift = 0)
    val sizeDiff = unpackSizeDiff(sdb)
    _assert(
      (sizeDiff == 0) ||
      (sizeDiff == 1) ||
      (
        (sizeDiff == -1) &&
        ((visitor eq Hamt.tombingVisitor) || (visitor eq AbstractHamt.tombingIfBlueVisitor))
      )
    )
    this.addToSize(sizeDiff)
    this.isBlueTree &= unpackIsBlue(sdb)
  }

  final def remove(k: K): Unit = {
    this.computeOrModify(k, null, Hamt.tombingVisitor[K, V])
  }

  @throws[Hamt.IllegalRemovalException]("if the value is not blue")
  final def removeBlueValue(k: K): Unit = {
    this.computeOrModify(k, this, AbstractHamt.tombingIfBlueVisitor[K, V, H])
  }

  final def copyToArray(tok: T1, flag: Boolean, nullIfBlue: Boolean): Array[E] = {
    this.copyToArrayInternal(tok, flag = flag, nullIfBlue = nullIfBlue)
  }

  final def copyToImmutable(): I = {
    val imm = this.copyToImmutableInternal(shift = 0)
    _assert((imm.size == this.size) && ((!this.isBlueTree) || imm.isBlueSubtree))
    imm
  }

  final override def equals(that: Any): Boolean = {
    if (equ(this, that)) {
      true
    } else {
      that match {
        case that: MutHamt[_, _, _, _, _, _, _] =>
          this.equalsInternal(that)
        case _ =>
          false
      }
    }
  }

  final override def hashCode: Int = {
    this.hashCodeInternal(0xe7019a9a)
  }

  final override def toString: String = {
    this.toString(pre = "MutHamt(", post = ")")
  }

  // Internal:

  private final def lookupOrNull(hash: Long, shift: Int): V = {
    this.getValueOrNodeOrNullOrTombstone(hash, shift) match {
      case null =>
        nullOf[V]
      case node: MutHamt[_, _, _, _, _, _, _] =>
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
    val contents = this.contents
    val logIdx = logicalIdx(hash, shift)
    val size = contents.length // always a power of 2
    val physIdx = physicalIdx(logIdx, size)
    contents(physIdx)
  }

  /** Returns the increase in size and isBlue */
  private final def visit[T](
    k: K,
    hash: Long,
    tok: T,
    visitor: Hamt.EntryVisitor[K, V, T], // only call if `newVal` is null
    newValue: V, // or null, to call `visitor`
    modify: Boolean,
    shift: Int,
  ): Int = {
    val contents = this.contents
    val size = contents.length // always a power of 2
    val logIdx = logicalIdx(hash, shift)
    val physIdx = physicalIdx(logIdx, size)
    contents(physIdx) match {
      case null =>
        this.visitEntryAbsent[T](k, hash, logIdx, physIdx, tok, visitor, newValue, shift = shift)
      case node: MutHamt[_, _, _, _, _, _, _] =>
        val nodeLogIdx = node.logIdx
        if (logIdx == nodeLogIdx) {
          node.asInstanceOf[H].visit(k, hash, tok, visitor, newValue = newValue, modify = modify, shift = shift + W)
        } else {
          if (isNull(newValue)) {
            // growing mutates the tree, so we must check
            // if we need to insert BEFORE growing:
            visitor.entryAbsent(k, tok) match {
              case null =>
                packSizeDiffAndBlue(0, true)
              case newVal =>
                this.growLevel(newSize = necessarySize(logIdx, nodeLogIdx), shift = shift)
                // now we can insert the new value:
                this.visit(k, hash, tok, visitor, newValue = newVal, modify = modify, shift = shift)
            }
          } else {
            this.insertOrOverwriteKnownIndex(logIdx, physIdx, hash, newValue, shift, op = OP_INSERT)
          }
        }
      case value =>
        val a = value.asInstanceOf[V]
        val hashA = a.key.hash
        if (hash == hashA) {
          if (!a.isTomb) {
            val newVal = if (isNull(newValue)) {
              visitor.entryPresent(k, a, tok)
            } else {
              newValue
            }
            if (modify) {
              if (equ(newValue, a)) {
                packSizeDiffAndBlue(0, isBlue(a))
              } else {
                _assert(newVal.key.hash == hashA)
                this.insertOrOverwriteKnownIndex(logIdx, physIdx, hash, newVal, shift, op = OP_UPDATE)
              }
            } else {
              _assert(equ(newVal, a))
              packSizeDiffAndBlue(0, true)
            }
          } else {
            this.visitEntryAbsent[T](k, hash, logIdx, physIdx, tok, visitor, newValue, shift = shift)
          }
        } else {
          val logIdxA = logicalIdx(hashA, shift)
          if (logIdx == logIdxA) {
            // hash collision at this level
            this.visitEntryAbsent[T](k, hash, logIdx, physIdx, tok, visitor, newValue, shift = shift)
          } else {
            if (isNull(newValue)) {
              visitor.entryAbsent(k, tok) match {
                case null =>
                  packSizeDiffAndBlue(0, true)
                case newVal =>
                  this.growLevel(newSize = necessarySize(logIdx, logIdxA), shift = shift)
                  this.visit(k, hash, tok, visitor, newValue = newVal, modify = modify, shift = shift)
              }
            } else {
              this.insertOrOverwriteKnownIndex(logIdx, physIdx, hash, newValue, shift, op = OP_INSERT)
            }
          }
        }
    }
  }

  private[this] final def visitEntryAbsent[T](
    k: K,
    hash: Long,
    logIdx: Int,
    physIdx: Int,
    tok: T,
    visitor: Hamt.EntryVisitor[K, V, T], // only call if `newVal` is null
    newValue: V, // or null, to call `visitor`
    shift: Int,
  ): Int = {
    val newVal = if (isNull(newValue)) {
      visitor.entryAbsent(k, tok)
    } else {
      newValue
    }
    newVal match {
      case null =>
        packSizeDiffAndBlue(0, true)
      case newVal =>
        _assert(newVal.key.hash == hash)
        this.insertOrOverwriteKnownIndex(logIdx, physIdx, hash, newVal, shift = shift, op = OP_INSERT)
    }
  }

  private final def insertOrOverwrite(hash: Long, value: V, shift: Int, op: Int): Int = {
    val contents = this.contents
    val size = contents.length // always a power of 2
    val logIdx = logicalIdx(hash, shift)
    val physIdx = physicalIdx(logIdx, size)
    insertOrOverwriteKnownIndex(logIdx = logIdx, physIdx = physIdx, hash = hash, value = value, shift = shift, op = op)
  }

  /** Returns the increase in size and isBlue */
  private[this] final def insertOrOverwriteKnownIndex(
    logIdx: Int,
    physIdx: Int,
    hash: Long,
    value: V,
    shift: Int,
    op: Int
  ): Int = {
    val contents = this.contents
    contents(physIdx) match {
      case null =>
        if (op == OP_UPDATE) {
          throwIllegalUpdate(value)
        } else {
          _assert(!value.isTomb)
          contents(physIdx) = value
          packSizeDiffAndBlue(1, isBlue(value))
        }
      case node: MutHamt[_, _, _, _, _, _, _] =>
        val nodeLogIdx = node.logIdx
        if (logIdx == nodeLogIdx) {
          node.asInstanceOf[H].insertOrOverwrite(hash, value, shift + W, op)
        } else {
          // growing mutates the tree, so we must check
          // for error conditions BEFORE doing it:
          if (op == OP_UPDATE) {
            // there is no chance that we'll be able to
            // update (even after growing):
            throwIllegalUpdate(value)
          } else {
            // we need to grow this level:
            val newSize = necessarySize(logIdx, nodeLogIdx)
            this.growLevel(newSize = newSize, shift = shift)
            // now we'll suceed for sure:
            this.insertOrOverwriteKnownIndex(logIdx, physicalIdx(logIdx, newSize), hash, value, shift, op)
          }
        }
      case ov =>
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
          if (!equ(ov, value)) {
            contents(physIdx) = value
          }
          val sizeDiff = if (value.isTomb) {
            // NB: we never overwrite a tombstone with a tombstone
            _assert(!ovv.isTomb)
            -1
          } else if (ovv.isTomb) {
            1
          } else {
            0
          }
          packSizeDiffAndBlue(sizeDiff, isBlueOrTomb(value))
        } else {
          val oLogIdx = logicalIdx(oh, shift)
          if (logIdx == oLogIdx) {
            // hash collision at this level,
            // so we go down 1 level
            val childShift = shift + W
            val childNode = {
              val cArr = new Array[AnyRef](2) // NB: 2 instead of 1
              cArr(physicalIdx(logicalIdx(oh, childShift), size = 2)) = ovv
              this.newNode(logIdx, cArr)
            }
            val r = childNode.insertOrOverwrite(hash, value, shift = childShift, op = op)
            contents(physIdx) = childNode
            _assert(unpackSizeDiff(r) == (if (value.isTomb) -1 else 1))
            r
          } else {
            if (op == OP_UPDATE) {
              // there is no chance that we'll be able to
              // update (even after growing):
              throwIllegalUpdate(value)
            } else {
              // grow this level:
              val newSize = necessarySize(logIdx, oLogIdx)
              this.growLevel(newSize = newSize, shift = shift)
              // now we'll suceed for sure:
              this.insertOrOverwriteKnownIndex(logIdx, physicalIdx(logIdx, newSize), hash, value, shift, op)
            }
          }
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

  private[this] final def growLevel(newSize: Int, shift: Int): Unit = {
    _assert((newSize & (newSize - 1)) == 0) // power of 2
    val newContents = new Array[AnyRef](newSize)
    val contents = this.contents
    val size = contents.length
    _assert(newSize > size)
    var idx = 0
    while (idx < size) {
      contents(idx) match {
        case null =>
          ()
        case node: MutHamt[_, _, _, _, _, _, _] =>
          val logIdx = node.logIdx
          val newPhysIdx = physicalIdx(logIdx, newSize)
          newContents(newPhysIdx) = node
        case value =>
          val logIdx = logicalIdx(value.asInstanceOf[V].key.hash, shift)
          val newPhysIdx = physicalIdx(logIdx, newSize)
          newContents(newPhysIdx) = value
      }
      idx += 1
    }
    this.contents = newContents
  }

  private final def copyToImmutableInternal(shift: Int): I = {
    val contents = this.contents
    var i = 0
    val len = contents.length
    var arity = 0
    while (i < len) {
      // we don't know the number of non-null items, so we pre-scan:
      if (contents(i) ne null) {
        arity += 1
      }
      i += 1
    }
    val arr = new Array[AnyRef](arity)
    var bitmap = 0L
    var size = 0
    var isBlueSubtree = true
    i = 0
    arity = 0
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: MutHamt[_, _, _, _, _, _, _] =>
          bitmap |= (1L << node.logIdx)
          val child: I = node.asInstanceOf[H].copyToImmutableInternal(shift = shift + W)
          size += child.size
          isBlueSubtree &= child.isBlueSubtree
          arr(arity) = box(child)
          arity += 1
        case value =>
          // TODO: we're also copying tombstones; we should probably do it in a smarter way (NB: prescan above)
          val v: V = value.asInstanceOf[V]
          bitmap |= (1L << logicalIdx(v.key.hash, shift = shift))
          size += (if (v.isTomb) 0 else 1)
          isBlueSubtree &= isBlueOrTomb(v)
          arr(arity) = value
          arity += 1
      }
      i += 1
    }

    this.newImmutableNode(sizeAndBlue = packSizeAndBlue(size, isBlueSubtree), bitmap = bitmap, contents = arr)
  }

  /**
   * The size if the array we'll need to store both logical indices
   * (considering bit collisions starting from the MSB).
   */
  private[this] final def necessarySize(logIdx1: Int, logIdx2: Int): Int = {
    _assert(logIdx1 != logIdx2)
    val diff = logIdx1 ^ logIdx2
    val necessaryBits = (
      java.lang.Integer.numberOfLeadingZeros(diff) - // this many bits are the same on the left
      (32 - 6) + // but we actually have 6-bit integers here instead of 32
      1 // and we never have a 1-element array (we start from 2 and always double when growing)
    )
    _assert(necessaryBits <= W)
    1 << necessaryBits // the size of the array which is addressable by this many bits
  }

  private[mcas] final def necessarySize_public(logIdx1: Int, logIdx2: Int): Int = {
    this.necessarySize(logIdx1, logIdx2)
  }

  /** Index into the imaginary 64-element sparse array */
  private[this] final def logicalIdx(hash: Long, shift: Int): Int = {
    // Note: this logic is duplicated in `Hamt` and `MemoryLocationOrdering`.
    ((hash << shift) >>> 58).toInt
  }

  private[mcas] final def logicalIdx_public(hash: Long, shift: Int): Int = {
    logicalIdx(hash, shift)
  }

  private[this] final def physicalIdx(logIdx: Int, size: Int): Int = {
    // size is always a power of 2; we need this many bits to
    // address a `size`-element array (i.e., the `width` of the
    // effective part of `logIdx`):
    val width = java.lang.Integer.numberOfTrailingZeros(size)
    // and we use as much of the higher bits of the 6-bit
    // integer as possible (see comment in Hamt#logicalIdx):
    logIdx >>> (6 - width)
    // Note, that at the last level, the LSB of `logIdx` is always
    // 2 zero bits. Here we naturally account for this fact (by
    // using `numberOfTrailingZeros` above).
  }

  private[mcas] final def physicalIdx_public(logIdx: Int, size: Int): Int = {
    physicalIdx(logIdx, size)
  }

  private[this] final def packSizeDiffAndBlue(sizeDiff: Int, isBlue: Boolean): Int = {
    _assert((sizeDiff == 0) || (sizeDiff == 1) || (sizeDiff == -1))
    val sd = sizeDiff + 1
    val bl = if (isBlue) 4 else 0
    bl | sd
  }

  private[mcas] final def packSizeDiffAndBlue_public(sizeDiff: Int, isBlue: Boolean): Int = {
    packSizeDiffAndBlue(sizeDiff, isBlue)
  }

  @inline
  private[this] final def unpackSizeDiff(sdb: Int): Int = {
    (sdb & 3) - 1
  }

  private[mcas] final def unpackSizeDiff_public(sdb: Int): Int = {
    unpackSizeDiff(sdb)
  }

  @inline
  private[this] final def unpackIsBlue(sdb: Int): Boolean = {
    (sdb & 4) != 0
  }

  private[mcas] final def unpackIsBlue_public(sdb: Int): Boolean = {
    unpackIsBlue(sdb)
  }
}
