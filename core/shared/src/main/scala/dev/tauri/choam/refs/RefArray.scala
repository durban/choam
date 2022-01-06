/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.syntax.all._

import mcas.MemoryLocation
import CompatPlatform.AtomicReferenceArray

import RefArray.RefArrayRef

/**
 * `items` continously stores 3 things
 * for each index:
 * - a `RefArrayRef` (at i)
 * - the value itself (at i + 1)
 * - a weak marker (at i + 2)
 */
private abstract class RefArray[A](
  val size: Int,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int, // LSB is array index
) extends RefIdOnly(i0, i1, i2, i3.toLong << 32)
  with Ref.Array[A] {

  def apply(idx: Int): Ref[A]

  protected val items: AtomicReferenceArray[AnyRef]

  protected[refs] final override def refToString(): String = {
    val h = (id0 ^ id1 ^ id2 ^ id3) & (~0xffff)
    s"RefArray[${size}]@${java.lang.Long.toHexString(h >> 16)}"
  }
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
  require(((size - 1) * 3 + 2) > (size - 1)) // avoid overflow

  protected final override val items: AtomicReferenceArray[AnyRef] = {
    // TODO: padding
    val ara = new AtomicReferenceArray[AnyRef](3 * size)
    val value = this.initial.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      val refIdx = 3 * i
      val itemIdx = refIdx + 1
      // val markerIdx = refIdx + 2
      ara.setPlain(refIdx, new RefArrayRef[A](this, itemIdx))
      ara.setPlain(itemIdx, value)
      // we're storing `ara` into a final field,
      // so `setPlain` is enough here, these
      // writes will be visible to any reader
      // of `this`
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Ref[A] = {
    require((idx >= 0) && (idx < size))
    val refIdx = 3 * idx
    // `RefArrayRef`s were initialized into
    // a final field (`items`), and they
    // never change, so we can read with plain:
    this.items.getPlain(refIdx).asInstanceOf[Ref[A]]
  }
}

private final class LazyRefArray[A](
  size: Int,
  initial: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int,
) extends RefArray[A](size, i0, i1, i2, i3) {

  require(size > 0)
  require(((size - 1) * 3 + 2) > (size - 1)) // avoid overflow

  protected final override val items: AtomicReferenceArray[AnyRef] = {
    // TODO: padding
    val ara = new AtomicReferenceArray[AnyRef](3 * size)
    val value = this.initial.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      ara.setPlain((3 * i) + 1, value)
      // we're storing `ara` into a final field,
      // so `setPlain` is enough here, these
      // writes will be visible to any reader
      // of `this`
      i += 1
    }
    ara
  }

  def apply(idx: Int): Ref[A] = {
    require((idx >= 0) && (idx < size))
    this.getOrCreateRef(idx)
  }

  private[this] def getOrCreateRef(i: Int): Ref[A] = {
    val refIdx = 3 * i
    val existing = this.items.getOpaque(refIdx)
    if (existing ne null) {
      // `RefArrayRef` has only final fields,
      // so its "safely initialized", so if
      // we've read something here with `getOpaque`,
      // then we also can see its fields:
      existing.asInstanceOf[Ref[A]]
    } else {
      val itemIdx = refIdx + 1
      val nv = new RefArrayRef[A](this, itemIdx)
      this.items.compareAndExchange(refIdx, null, nv) match {
        case null => nv // we're the first
        case other => other.asInstanceOf[Ref[A]]
      }
    }
  }
}

private final class EmptyRefArray[A](size: Int) extends RefArray[A](0, 0L, 0L, 0L, 0) {

  require(size === 0)

  final override def apply(idx: Int): Ref[A] =
    throw new IllegalArgumentException("EmptyRefArray")

  protected final override val items: AtomicReferenceArray[AnyRef] =
    null // unused
}

private object RefArray {

  private[refs] final class RefArrayRef[A](
    array: RefArray[A],
    physicalIdx: Int,
  ) extends Ref[A] with MemoryLocation[A] {

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

    final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
      array.items.get(physicalIdx + 1).asInstanceOf[WeakReference[AnyRef]]

    final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
      array.items.compareAndSet(physicalIdx + 1, ov, nv)

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
      this.physicalIdx / 3

    private[choam] final override def dummy(v: Long): Long =
      v ^ id2
  }
}
