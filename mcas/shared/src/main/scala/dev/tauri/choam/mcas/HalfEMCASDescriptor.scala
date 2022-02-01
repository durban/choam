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
import scala.collection.AbstractIterator
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

  private final object Empty extends LogMap {
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

  private final class LogMap1(private val v1: HalfWordDescriptor[_]) extends LogMap {
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
      case that: LogMap1 => this.v1 eq that.v1
      case _ => false
    }
    final override def hashCode: Int =
      MurmurHash3.finalizeHash(v1.##, 1)
  }

  private[this] final val MaxArraySize =
    1024 // TODO: this is almost certainly too big

  /** Invariant: has more than 1, and at most `MaxArraySize` items */
  private final class LogMapArray(
    private val array: Array[HalfWordDescriptor[Any]],
    //private[this] val ordering: Ordering[HalfWordDescriptor[Any]],
  ) extends LogMap {
    require((array.length > 1) && (array.length <= MaxArraySize))
    def this(v1: HalfWordDescriptor[_], v2: HalfWordDescriptor[_]) = {
      this({
        require(v1.address ne v2.address)
        val v1Any = v1.asInstanceOf[HalfWordDescriptor[Any]]
        val v2Any = v2.asInstanceOf[HalfWordDescriptor[Any]]
        val arr = new Array[HalfWordDescriptor[Any]](2)
        if (MemoryLocation.orderingInstance.lt(v1Any.address, v2Any.address)) { // v1 < v2
          arr(0) = v1Any
          arr(1) = v2Any
        } else { // v1 > v2
          assert(MemoryLocation.orderingInstance.gt(v1Any.address, v2Any.address))
          arr(0) = v2Any
          arr(1) = v1Any
        }
        arr
      })
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
        new LogMapArray(newArr)
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
          new LogMapArray(newArr)
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
      val ref = loc.asInstanceOf[MemoryLocation[Any]]
      val ord = MemoryLocation.orderingInstance[Any]
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
          -(l + 1)
        }
      }
      go(l = 0, r = array.length - 1)
    }
  }

  /** Invariant: `treeMap` has more items than `MaxArraySize` */
  private final class LogMapTree(private val treeMap: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]]) extends LogMap {
    require(treeMap.size > MaxArraySize)
    def this(arr: Array[HalfWordDescriptor[Any]], extra: HalfWordDescriptor[Any]) = {
      this({
        val b = TreeMap.newBuilder[MemoryLocation[Any], HalfWordDescriptor[Any]](
          MemoryLocation.orderingInstance[Any]
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
      treeMap.contains(ref.asInstanceOf[MemoryLocation[Any]])
    final override def updated[A](k: MemoryLocation[A], v: HalfWordDescriptor[A]): LogMap =
      new LogMapTree(treeMap.updated(k.asInstanceOf[MemoryLocation[Any]], v.cast[Any]))
    final override def getOrElse[A](k: MemoryLocation[A], default: HalfWordDescriptor[A]) =
      treeMap.getOrElse(k.asInstanceOf[MemoryLocation[Any]], default).asInstanceOf[HalfWordDescriptor[A]]
    final override def equals(that: Any): Boolean = that match {
      case that: LogMapTree => this.treeMap == that.treeMap
      case _ => false
    }
    final override def hashCode: Int =
      treeMap.##
  }
}

// TODO: this really should have a better name
final class HalfEMCASDescriptor private (
  private val map: LogMap, // TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]],
  private val validTsBoxed: java.lang.Long,
  val readOnly: Boolean,
  private val versionIncr: Long,
  private val versionCas: HalfWordDescriptor[java.lang.Long], // can be null
) {

  final def validTs: Long =
    this.validTsBoxed // unboxing happens

  final def size: Int =
    this.map.size + (if (this.versionCas ne null) 1 else 0)

  final def iterator(): Iterator[HalfWordDescriptor[_]] = {
    if (this.versionCas eq null) {
      this.map.valuesIterator
    } else {
      val underlying = this.map.valuesIterator
      val vc = this.versionCas
      new AbstractIterator[HalfWordDescriptor[_]] {
        private[this] var done: Boolean = false
        final override def hasNext: Boolean = {
          underlying.hasNext || (!done)
        }
        final override def next(): HalfWordDescriptor[_] = {
          if (underlying.hasNext) {
            underlying.next()
          } else if (!done) {
            done = true
            vc
          } else {
            throw new NoSuchElementException
          }
        }
      }
    }
  }

  final def isValidHwd[A](hwd: HalfWordDescriptor[A]): Boolean = {
    hwd.version <= this.validTs
  }

  private final def withValidTsBoxed(newBoxed: java.lang.Long): HalfEMCASDescriptor =  {
    new HalfEMCASDescriptor(
      map = this.map,
      validTsBoxed = newBoxed,
      readOnly = this.readOnly,
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[mcas] final def withNoNewVersion: HalfEMCASDescriptor = {
    require(this.versionCas eq null)
    new HalfEMCASDescriptor(
      map = this.map,
      validTsBoxed = this.validTsBoxed,
      readOnly = this.readOnly,
      versionIncr = 0L,
      versionCas = null,
    )
  }

  private[mcas] final def newVersion: Long =
    this.validTs + this.versionIncr

  private[mcas] final def nonEmpty: Boolean =
    this.map.nonEmpty

  private[choam] final def add[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    val d = desc.cast[Any]
    // Note, that it is important, that we don't allow
    // adding an already included ref; the Exchanger
    // depends on this behavior:
    require(!this.map.contains(d.address))
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTsBoxed,
      this.readOnly && desc.readOnly,
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[choam] final def overwrite[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    require(desc.version <= this.validTs)
    val d = desc.cast[Any]
    require(this.map.contains(d.address))
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[choam] final def addOrOverwrite[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    require(desc.version <= this.validTs)
    val d = desc.cast[Any]
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[mcas] final def addVersionCas(commitTsRef: MemoryLocation[Long]): HalfEMCASDescriptor = {
    require(this.versionCas eq null)
    require(!this.readOnly)
    require(this.versionIncr > 0L)
    val hwd = HalfWordDescriptor[java.lang.Long](
      commitTsRef.asInstanceOf[MemoryLocation[java.lang.Long]],
      ov = this.validTsBoxed, // no boxing here
      nv = this.newVersion, // boxing happens here
      version = Version.Start, // the version's version is unused/arbitrary
    )
    new HalfEMCASDescriptor(
      map = this.map,
      validTsBoxed = this.validTsBoxed,
      readOnly = false,
      versionIncr = this.versionIncr,
      versionCas = hwd,
    )
  }

  private[choam] final def getOrElseNull[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
    val r = ref.asInstanceOf[MemoryLocation[Any]]
    this.map.getOrElse(r, null) match {
      case null => null
      case hwd => hwd.cast[A]
    }
  }

  private[mcas] final def validateAndTryExtend(
    commitTsRef: MemoryLocation[Long],
    ctx: MCAS.ThreadContext,
  ): HalfEMCASDescriptor = {
    // NB: we must read the commitTs *before* the `ctx.validate(this)`
    val newValidTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    require(newValidTsBoxed.longValue > this.validTs)
    if (ctx.validate(this)) {
      new HalfEMCASDescriptor(
        map = this.map,
        validTsBoxed = newValidTsBoxed,
        readOnly = this.readOnly,
        versionIncr = this.versionIncr,
        versionCas = this.versionCas,
      )
    } else {
      null
    }
  }

  final override def toString: String = {
    val m = map.valuesIterator.mkString("[", ", ", "]")
    val vi = if (versionIncr == HalfEMCASDescriptor.DefaultVersionIncr) {
      ""
    } else {
      s", versionIncr = ${versionIncr}"
    }
    val vc = if (versionCas eq null) {
      ""
    } else {
      s", versionCas = ${versionCas}"
    }
    s"HalfEMCASDescriptor(${m}, validTs = ${validTs}, readOnly = ${readOnly}${vi}${vc})"
  }

  final override def equals(that: Any): Boolean = {
    that match {
      case that: HalfEMCASDescriptor =>
        (this eq that) || (
          (this.readOnly == that.readOnly) &&
          (this.validTsBoxed eq that.validTsBoxed) &&
          (this.versionIncr == that.versionIncr) &&
          (this.versionCas == that.versionCas) &&
          (this.map == that.map)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    val h1 = MurmurHash3.mix(0xefebde66, System.identityHashCode(this.validTsBoxed))
    val h2 = MurmurHash3.mix(h1, this.readOnly.##)
    val h3 = MurmurHash3.mix(h2, this.versionIncr.##)
    val h4 = MurmurHash3.mix(h3, this.versionCas.##)
    val h5 = MurmurHash3.mix(h4, this.map.##)
    MurmurHash3.finalizeHash(h5, this.map.size)
  }
}

object HalfEMCASDescriptor {

  private final val DefaultVersionIncr =
    Version.Incr

  private[mcas] def empty(commitTsRef: MemoryLocation[Long], ctx: MCAS.ThreadContext): HalfEMCASDescriptor = {
    val validTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    new HalfEMCASDescriptor(
      LogMap.empty, // TreeMap.empty(MemoryLocation.orderingInstance[Any]),
      validTsBoxed = validTsBoxed,
      readOnly = true,
      versionIncr = DefaultVersionIncr,
      versionCas = null,
    )
  }

  private[mcas] final def merge(
    a: HalfEMCASDescriptor,
    b: HalfEMCASDescriptor,
    ctx: MCAS.ThreadContext,
  ): HalfEMCASDescriptor = {
    require(a.versionIncr == b.versionIncr)
    // TODO: it is unclear, how should an exchange work when
    // TODO: both sides already touched the same refs;
    // TODO: for now, we only allow disjoint logs
    val it = b.map.valuesIterator
    var merged: HalfEMCASDescriptor = a
    while (it.hasNext) {
      // this also takes care of `readOnly`:
      merged = merged.add(it.next()) // throws in case of conflict
    }

    // we temporarily choose the older `validTs`,
    // but will extend if they're not equal:
    val needToExtend = if (a.validTs < b.validTs) {
      merged = merged.withValidTsBoxed(a.validTsBoxed)
      true
    } else if (a.validTs > b.validTs) {
      merged = merged.withValidTsBoxed(b.validTsBoxed)
      true
    } else {
      // they're equal, no need to extend:
      false
    }
    if (needToExtend) {
      // this will be null, if we cannot extend,
      // in which case we return null:
      merged = ctx.validateAndTryExtend(merged)
    }
    merged
  }
}
