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
package refs

import java.lang.ref.WeakReference

import internal.mcas.{ MemoryLocation, Version }
import RefArray.RefArrayRef

private abstract class RefArray[A](
  val size: Int,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int, // LSB is array index
) extends RefIdOnly(i0, i1, i2, i3.toLong << 32)
  with Ref.UnsealedArray[A] {

  protected def items: Array[AnyRef]
}

private final class StrictRefArray[A](
  size: Int,
  initial: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int,
) extends RefArray[A](size, i0, i1, i2, i3) {

  final override val items: Array[AnyRef] = {
    val ara = new Array[AnyRef](4 * size)
    val value = initial.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      val refIdx = 4 * i
      val itemIdx = refIdx + 1
      val versionIdx = refIdx + 2
      // val markerIdx = refIdx + 3
      ara(refIdx) = new RefArrayRef[A](this, itemIdx)
      ara(itemIdx) = value
      ara(versionIdx) = Version.BoxedStart
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Option[Ref[A]] = {
    this.checkIndex(idx)
    Some(this.unsafeGet(idx))
  }

  final override def unsafeGet(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    val refIdx = 4 * idx
    CompatPlatform.checkArrayIndexIfScalaJs(refIdx, this.items.length)
    this.items(refIdx).asInstanceOf[Ref[A]]
  }
}

private final class LazyRefArray[A]( // TODO: rename to SparseRefArray
  size: Int,
  initial: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int,
) extends RefArray[A](size, i0, i1, i2, i3) {

  private[this] val initVal: AnyRef =
    initial.asInstanceOf[AnyRef]

  final override val items: Array[AnyRef] = {
    val ara = new Array[AnyRef](4 * size)
    var i = 0
    while (i < size) {
      val itemIdx = (4 * i) + 1
      ara(itemIdx) = initVal
      val versionIdx = itemIdx + 1
      ara(versionIdx) = Version.BoxedStart
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Option[Ref[A]] = {
    this.checkIndex(idx)
    Some(this.getOrCreateRef(idx))
  }

  final override def unsafeGet(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx)
  }

  private[this] final def getOrCreateRef(i: Int): Ref[A] = {
    val items = this.items
    val refIdx = 4 * i
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
  ) extends UnsealedRef[A] with MemoryLocation[A] {

    final override def unsafeGetVolatile(): A =
      array.items(physicalIdx).asInstanceOf[A]

    final override def unsafeGetPlain(): A =
      array.items(physicalIdx).asInstanceOf[A]

    final override def unsafeSetVolatile(nv: A): Unit =
      array.items(physicalIdx) = nv.asInstanceOf[AnyRef]

    final override def unsafeSetPlain(nv: A): Unit =
      array.items(physicalIdx) = nv.asInstanceOf[AnyRef]

    final override def unsafeCasVolatile(ov: A, nv: A): Boolean = {
      val wit = array.items(physicalIdx)
      if (wit eq ov.asInstanceOf[AnyRef]) {
        array.items(physicalIdx) = nv.asInstanceOf[AnyRef]
        true
      } else {
        false
      }
    }

    final override def unsafeCmpxchgVolatile(ov: A, nv: A): A = {
      val wit = array.items(physicalIdx)
      if (wit eq ov.asInstanceOf[AnyRef]) {
        array.items(physicalIdx) = nv.asInstanceOf[AnyRef]
      }
      wit.asInstanceOf[A]
    }

    final override def unsafeGetVersionVolatile(): Long =
      array.items(physicalIdx + 1).asInstanceOf[Long]

    final override def unsafeCasVersionVolatile(ov: Long, nv: Long): Boolean = {
      val idx = physicalIdx + 1
      val currentValue: Long = array.items(idx).asInstanceOf[Long]
      if (currentValue == ov) {
        array.items(idx) = nv.asInstanceOf[AnyRef]
        true
      } else {
        false
      }
    }

    final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long = {
      val idx = physicalIdx + 1
      val currentValue: Long = array.items(idx).asInstanceOf[Long]
      if (currentValue == ov) {
        array.items(idx) = nv.asInstanceOf[AnyRef]
      }
      currentValue
    }

    final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
      array.items(physicalIdx + 2).asInstanceOf[WeakReference[AnyRef]]

    final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean = {
      val markerIdx = physicalIdx + 2
      val wit = array.items(markerIdx)
      if (wit eq ov) {
        array.items(markerIdx) = nv
        true
      } else {
        false
      }
    }

    final override def id0: Long =
      array.id0

    final override def id1: Long =
      array.id1

    final override def id2: Long =
      array.id2

    final override def id3: Long =
      array.id3 | this.logicalIdx.toLong

    final override def toString: String = {
      refs.refStringFromIdsAndIdx(id0, id1, id2, id3, this.logicalIdx)
    }

    private[this] final def logicalIdx: Int =
      this.physicalIdx / 4 // truncated division is OK here

    private[choam] final override def dummy(v: Long): Long =
      v ^ id2
  }
}
