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

import java.util.concurrent.atomic.AtomicReferenceArray
import java.lang.ref.WeakReference

import internal.mcas.{ MemoryLocation, Version }
import RefArray.RefArrayRef

/**
 * `items` continously stores 4 things
 * for each index:
 * - a `RefArrayRef` (at i)
 * - the value/item itself (at i + 1)
 * - the version (a Long; at i + 2)
 * - a weak marker (at i + 3)
 */
private abstract class RefArray[A](
  val size: Int,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int, // LSB is array index
) extends RefIdOnly(i0, i1, i2, i3.toLong << 32)
  with Ref.UnsealedArray[A] {

  // TODO: use an array VarHandle to avoid extra indirection
  protected def items: AtomicReferenceArray[AnyRef]
}

private final class StrictRefArray[A](
  size: Int,
  initial: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int,
) extends RefArray[A](size, i0, i1, i2, i3) {

  require(size > 0)
  require(((size - 1) * 4 + 3) > (size - 1)) // avoid overflow

  protected final override val items: AtomicReferenceArray[AnyRef] = {
    val ara = new AtomicReferenceArray[AnyRef](4 * size)
    val value = this.initial.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      val refIdx = 4 * i
      val itemIdx = refIdx + 1
      val versionIdx = refIdx + 2
      // val markerIdx = refIdx + 3
      ara.setPlain(refIdx, new RefArrayRef[A](this, itemIdx))
      ara.setPlain(itemIdx, value)
      ara.setPlain(versionIdx, Version.BoxedStart)
      // we're storing `ara` into a final field,
      // so `setPlain` is enough here, these
      // writes will be visible to any reader
      // of `this`
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
      val refIdx = 4 * idx
      // `RefArrayRef`s were initialized into
      // a final field (`items`), and they
      // never change, so we can read with plain:
      this.items.getPlain(refIdx).asInstanceOf[Ref[A]]
    } else {
      null
    }
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

  require(size > 0)
  require(((size - 1) * 4 + 3) > (size - 1)) // avoid overflow

  private[this] val initVal: AnyRef =
    initial.asInstanceOf[AnyRef]

  protected final override val items: AtomicReferenceArray[AnyRef] = {
    val ara = new AtomicReferenceArray[AnyRef](4 * size)
    var i = 0
    while (i < size) {
      val itemIdx = (4 * i) + 1
      ara.setPlain(itemIdx, initVal)
      val versionIdx = itemIdx + 1
      ara.setPlain(versionIdx, Version.BoxedStart)
      // we're storing `ara` into a final field,
      // so `setPlain` is enough here, these
      // writes will be visible to any reader
      // of `this.items`
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
    val refIdx = 4 * i
    val existing = items.getOpaque(refIdx)
    if (existing ne null) {
      // `RefArrayRef` has only final fields,
      // so it's "safely initialized", so if
      // we've read something here with `getOpaque`,
      // then we also can see its fields:
      existing.asInstanceOf[Ref[A]]
    } else {
      val itemIdx = refIdx + 1
      val nv = new RefArrayRef[A](this, itemIdx)
      items.compareAndExchange(refIdx, null, nv) match {
        case null => nv // we're the first
        case other => other.asInstanceOf[Ref[A]]
      }
    }
  }
}

private object RefArray {

  private[refs] final class RefArrayRef[A](
    array: RefArray[A],
    physicalIdx: Int,
  ) extends UnsealedRef[A] with MemoryLocation[A] {

    final override def unsafeGetVolatile(): A =
      array.items.get(physicalIdx).asInstanceOf[A]

    final override def unsafeGetPlain(): A =
      array.items.getPlain(physicalIdx).asInstanceOf[A]

    final override def unsafeSetVolatile(nv: A): Unit =
      array.items.set(physicalIdx, nv.asInstanceOf[AnyRef])

    final override def unsafeSetPlain(nv: A): Unit =
      array.items.setPlain(physicalIdx, nv.asInstanceOf[AnyRef])

    final override def unsafeCasVolatile(ov: A, nv: A): Boolean =
      array.items.compareAndSet(physicalIdx, ov.asInstanceOf[AnyRef], nv.asInstanceOf[AnyRef])

    final override def unsafeCmpxchgVolatile(ov: A, nv: A): A =
      array.items.compareAndExchange(physicalIdx, ov.asInstanceOf[AnyRef], nv.asInstanceOf[AnyRef]).asInstanceOf[A]

    final override def unsafeGetVersionVolatile(): Long =
      array.items.get(physicalIdx + 1).asInstanceOf[Long]

    final override def unsafeCasVersionVolatile(ov: Long, nv: Long): Boolean = {
      val items = array.items
      val idx = physicalIdx + 1
      // unfortunately we have to be careful with object identity:
      val current: AnyRef = items.get(idx)
      val currentValue: Long = current.asInstanceOf[Long]
      if (currentValue == ov) {
        items.compareAndSet(idx, current, nv.asInstanceOf[AnyRef])
      } else {
        false
      }
    }

    final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long = {
      val items = array.items
      val idx = physicalIdx + 1
      // unfortunately we have to be careful with object identity:
      val current: AnyRef = items.get(idx)
      val currentValue: Long = current.asInstanceOf[Long]
      if (currentValue == ov) {
        val wit = items.compareAndExchange(idx, current, nv.asInstanceOf[AnyRef])
        wit.asInstanceOf[Long]
      } else {
        currentValue
      }
    }

    final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
      array.items.get(physicalIdx + 2).asInstanceOf[WeakReference[AnyRef]]

    final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
      array.items.compareAndSet(physicalIdx + 2, ov, nv)

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
