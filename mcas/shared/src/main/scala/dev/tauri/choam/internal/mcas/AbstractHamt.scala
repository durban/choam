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

import scala.util.hashing.MurmurHash3
import scala.collection.AbstractIterator

private[mcas] abstract class AbstractHamt[K <: Hamt.HasHash, V <: Hamt.HasKey[K], E <: AnyRef, T1, T2, H <: AbstractHamt[K, V, E, T1, T2, H]] protected[mcas] () { self: H =>

  protected def newArray(size: Int): Array[E]

  protected def convertForArray(a: V, tok: T1, flag: Boolean): E

  protected def predicateForForAll(a: V, tok: T2): Boolean

  protected def isBlue(a: V): Boolean

  def size: Int

  protected def contentsArr: Array[AnyRef]

  protected def insertInternal(v: V): H

  /**
   * Evaluates `predicateForForAll` (implemented
   * in a subclass) for the values, short-circuits
   * on `false`.
   */
  final def forAll(tok: T2): Boolean = {
    this.forAllInternal(tok)
  }

  /** Only call on the root! */
  final def valuesIterator: Iterator[V] = {
    new AbstractIterator[V] {

      private[this] val arrays =
        new Array[Array[AnyRef]](11)

      private[this] val indices =
        new Array[Int](11)

      private[this] var depth: Int =
        0

      private[this] var loadedNext: V =
        nullOf[V]

      locally {
        this.arrays(0) = self.contentsArr
        this.indices(0) = -1
        this.loadNext()
      }

      final override def hasNext: Boolean = {
        !isNull(this.loadedNext)
      }

      final override def next(): V = {
        if (this.hasNext) {
          val res = this.loadedNext
          this.loadedNext = nullOf[V]
          this.loadNext()
          res
        } else {
          throw new NoSuchElementException
        }
      }

      @tailrec
      private[this] final def loadNext(): Unit = {
        val d = this.depth
        val idx = this.indices(d) + 1
        this.indices(d) = idx
        val arr = this.arrays(d)
        if (idx < arr.length) {
          arr(idx) match {
            case null =>
              // skip empty slot:
              this.loadNext()
            case node: AbstractHamt[_, _, _, _, _, _] =>
              // descend:
              val newDepth = d + 1
              this.depth = newDepth
              this.indices(newDepth) = -1
              this.arrays(newDepth) = node.contentsArr
              this.loadNext()
            case a =>
              // found it:
              this.loadedNext = a.asInstanceOf[V]
          }
        } else {
          // ascend:
          val newDepth = d - 1
          this.depth = newDepth
          if (newDepth >= 0) {
            this.loadNext()
          } // else: at end
        }
      }
    }
  }

  final def toString(pre: String, post: String): String = {
    val sb = new java.lang.StringBuilder(pre)
    val _ = this.toStringInternal(sb, first = true)
    sb.append(post)
    sb.toString()
  }

  protected final def copyToArrayInternal(tok: T1, flag: Boolean, nullIfBlue: Boolean): Array[E] = {
    val arr = this.newArray(this.size)
    val endAndBlue = this.copyIntoArray(arr, 0, tok, flag = flag)
    assert(unpackSize(endAndBlue) == arr.length)
    if (nullIfBlue && unpackBlue(endAndBlue)) {
      null
    } else {
      arr
    }
  }

  private final def copyIntoArray(arr: Array[E], start: Int, tok: T1, flag: Boolean): Int = {
    val contents = this.contentsArr
    var i = 0
    var arrIdx = start
    var isBlueSt = true
    val len = contents.length
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: AbstractHamt[_, _, _, _, _, _] =>
          val arrIdxAndBlue = node.asInstanceOf[H].copyIntoArray(arr, arrIdx, tok, flag = flag)
          arrIdx = unpackSize(arrIdxAndBlue)
          isBlueSt &= unpackBlue(arrIdxAndBlue)
        case a =>
          // temporary assertion to diagnose a bug here:
          if (arrIdx >= arr.length) {
            throw new AssertionError(
              s"indexing array of length ${arr.length} with index ${arrIdx} (" +
              s"a = ${a}; arr = ${arr.mkString("[", ", ", "]")}; " +
              s"contents = ${contents.mkString("[", ", ", "]")})"
            )
          }
          // end of temporary assertion
          arr(arrIdx) = convertForArray(a.asInstanceOf[V], tok, flag = flag)
          arrIdx += 1
          isBlueSt &= isBlue(a.asInstanceOf[V])
      }
      i += 1
    }
    packSizeAndBlue(arrIdx, isBlueSt)
  }

  protected final def insertIntoHamt(into: AbstractHamt[_, _, _, _, _, _]): H = {
    val contents = this.contentsArr
    var i = 0
    var curr = into
    val len = contents.length
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: AbstractHamt[_, _, _, _, _, _] =>
          curr = node.insertIntoHamt(curr)
        case a =>
          curr = curr.asInstanceOf[H].insertInternal(a.asInstanceOf[V])
      }
      i += 1
    }
    curr.asInstanceOf[H]
  }

  private final def forAllInternal(tok: T2): Boolean = {
    val contents = this.contentsArr
    var i = 0
    val len = contents.length
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: AbstractHamt[_, _, _, _, _, _] =>
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

  protected def equalsInternal(that: AbstractHamt[_, _, _, _, _, _]): Boolean = {
    // Insertions are not order-dependent, and
    // there is no deletion, so HAMTs with the
    // same values always have the same tree
    // structure. So we can just traverse the
    // 2 trees at the same time.
    val thisContents = this.contentsArr
    val thatContents = that.contentsArr
    val thisLen = thisContents.length
    val thatLen = thatContents.length
    if (thisLen == thatLen) {
      var i = 0
      while (i < thisLen) {
        val iOk = thisContents(i) match {
          case null =>
            thatContents(i) eq null
          case thisNode: AbstractHamt[_, _, _, _, _, _] =>
            thatContents(i) match {
              case thatNode: AbstractHamt[_, _, _, _, _, _] =>
                thisNode.equalsInternal(thatNode)
              case _ => // including null
                false
            }
          case thisValue =>
            thatContents(i) match {
              case null | (_: Hamt[_, _, _, _, _, _]) =>
                false
              case thatValue =>
                thisValue == thatValue
            }
        }
        if (iOk) {
          i += 1
        } else {
          return false // scalafix:ok
        }
      }
      true
    } else {
      false
    }
  }

  protected final def hashCodeInternal(s: Int): Int = {
    val contents = this.contentsArr
    var i = 0
    var curr = s
    val len = contents.length
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: AbstractHamt[_, _, _, _, _, _] =>
          curr = node.hashCodeInternal(curr)
        case a =>
          curr = MurmurHash3.mix(curr, (a.asInstanceOf[V].key.hash >>> 32).toInt)
          curr = MurmurHash3.mix(curr, a.##)
      }
      i += 1
    }
    curr
  }

  private final def toStringInternal(sb: java.lang.StringBuilder, first: Boolean): Boolean = {
    val contents = this.contentsArr
    var i = 0
    val len = contents.length
    var fst = first
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: AbstractHamt[_, _, _, _, _, _] =>
          fst = node.toStringInternal(sb, fst)
        case a =>
          if (!fst) {
            sb.append(", ")
          } else {
            fst = false
          }
          sb.append(a.toString)
      }
      i += 1
    }
    fst
  }

  // TODO: this is duplicated with `Hamt`
  protected[this] final def packSizeAndBlue(size: Int, isBlue: Boolean): Int = {
    if (isBlue) size
    else -size
  }

  @inline
  protected[this] final def unpackSize(sb: Int): Int = {
    java.lang.Math.abs(sb)
  }

  @inline
  protected[this] final def unpackBlue(sb: Int): Boolean = {
    sb >= 0
  }
}
