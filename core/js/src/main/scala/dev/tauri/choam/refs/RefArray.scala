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
package refs

import java.lang.ref.WeakReference

import internal.mcas.{ MemoryLocation, Version, RefIdGen }
import RefArray.RefArrayRef

private abstract class RefArray[A](
  val size: Int,
  _idBase: Long,
) extends RefIdOnlyN(_idBase)
  with Ref.UnsealedArray[A] {

  protected def items: Array[AnyRef]

  final def idBase: Long =
    this.id
}

private final class StrictRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends RefArray[A](__size, _idBase) {

  final override val items: Array[AnyRef] = {
    val ara = new Array[AnyRef](3 * size)
    val value = initial.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      val refIdx = 3 * i
      val itemIdx = refIdx + 1
      val versionIdx = refIdx + 2
      ara(refIdx) = new RefArrayRef[A](this, itemIdx)
      ara(itemIdx) = value
      ara(versionIdx) = Version.BoxedStart
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeGet(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrNull(idx)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < size)) {
      val refIdx = 3 * idx
      CompatPlatform.checkArrayIndexIfScalaJs(refIdx, this.items.length)
      this.items(refIdx).asInstanceOf[Ref[A]]
    } else {
      null
    }
  }
}

private final class SparseRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends RefArray[A](__size, _idBase) {

  private[this] val initVal: AnyRef =
    initial.asInstanceOf[AnyRef]

  final override val items: Array[AnyRef] = {
    val ara = new Array[AnyRef](3 * size)
    var i = 0
    while (i < size) {
      val itemIdx = (3 * i) + 1
      ara(itemIdx) = initVal
      val versionIdx = itemIdx + 1
      ara(versionIdx) = Version.BoxedStart
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeGet(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < size)) {
      this.getOrCreateRef(idx)
    } else {
      null
    }
  }

  private[this] final def getOrCreateRef(i: Int): Ref[A] = {
    val items = this.items
    val refIdx = 3 * i
    CompatPlatform.checkArrayIndexIfScalaJs(refIdx, items.length)
    val existing = items(refIdx)
    if (existing ne null) {
      existing.asInstanceOf[Ref[A]]
    } else {
      val itemIdx = refIdx + 1
      val nv = new RefArrayRef[A](this, itemIdx)
      items(refIdx) = nv
      nv
    }
  }
}

private object RefArray {

  private[refs] final class RefArrayRef[A](
    array: RefArray[A],
    physicalIdx: Int,
  ) extends core.RefGetAxn[A] with UnsealedRef[A] with MemoryLocation[A] {

    final override def hashCode: Int =
      this.id.toInt

    final override def unsafeGetV(): A =
      array.items(physicalIdx).asInstanceOf[A]

    final override def unsafeGetP(): A =
      array.items(physicalIdx).asInstanceOf[A]

    final override def unsafeSetV(nv: A): Unit =
      array.items(physicalIdx) = nv.asInstanceOf[AnyRef]

    final override def unsafeSetP(nv: A): Unit =
      array.items(physicalIdx) = nv.asInstanceOf[AnyRef]

    final override def unsafeCasV(ov: A, nv: A): Boolean = {
      val wit = array.items(physicalIdx)
      if (wit eq ov.asInstanceOf[AnyRef]) {
        array.items(physicalIdx) = nv.asInstanceOf[AnyRef]
        true
      } else {
        false
      }
    }

    final override def unsafeCmpxchgV(ov: A, nv: A): A = {
      val wit = array.items(physicalIdx)
      if (wit eq ov.asInstanceOf[AnyRef]) {
        array.items(physicalIdx) = nv.asInstanceOf[AnyRef]
      }
      wit.asInstanceOf[A]
    }

    final override def unsafeCmpxchgR(ov: A, nv: A): A = {
      this.unsafeCmpxchgV(ov, nv)
    }

    final override def unsafeGetVersionV(): Long =
      array.items(physicalIdx + 1).asInstanceOf[Long]

    final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long = {
      val idx = physicalIdx + 1
      val currentValue: Long = array.items(idx).asInstanceOf[Long]
      if (currentValue == ov) {
        array.items(idx) = nv.asInstanceOf[AnyRef]
      }
      currentValue
    }

    final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
      impossible("RefArrayRef#unsafeGetMarkerV called on JS")

    final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
      impossible("RefArrayRef#unsafeCasMarkerV called on JS")

    final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
      impossible("RefArrayRef#unsafeCmpxchgMarkerR called on JS")

    final override def id: Long = {
      RefIdGen.compute(array.idBase, this.logicalIdx)
    }

    final override def toString: String = {
      refs.refArrayRefToString(array.idBase, this.logicalIdx)
    }

    private[this] final def logicalIdx: Int =
      this.physicalIdx / 3 // truncated division is OK here

    private[choam] final override def dummy(v: Byte): Long =
      v ^ id
  }
}
