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

import internal.mcas.MemoryLocation

private abstract class RefArray[A](
  __size: Int,
  init: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int, // LSB is array index
  sparse: Boolean,
) extends RefArrayBase[A](__size, sparse, init.asInstanceOf[AnyRef], i0, i1, i2, i3.toLong << 32)
  with Ref.UnsealedArray[A] {

  final override def size: Int =
    this._size
}

private final class StrictRefArray[A](
  size: Int,
  initial: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int,
) extends RefArray[A](size, initial, i0, i1, i2, i3, sparse = false) {

  require((size > 0) && (((size - 1) * 3 + 2) > (size - 1))) // avoid overflow

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeGet(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrNull(idx)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < size)) {
      val refIdx = 3 * idx
      // `RefArrayRef`s were initialized into
      // a final field (`items`), and they
      // never change, so we can read with plain:
      this.getPlain(refIdx).asInstanceOf[Ref[A]]
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
) extends RefArray[A](size, initial, i0, i1, i2, i3, sparse = true) {

  require((size > 0) && (((size - 1) * 3 + 2) > (size - 1))) // avoid overflow

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

    val refIdx = 3 * i
    val existing = this.getOpaque(refIdx)
    if (existing ne null) {
      // `RefArrayRef` has only final fields,
      // so it's "safely initialized", so if
      // we've read something here with `getOpaque`,
      // then we also can see its fields:
      existing.asInstanceOf[Ref[A]]
    } else {
      val nv = new RefArrayRef[A](this, i)
      this.cmpxchgVolatile(refIdx, null, nv) match {
        case null => nv // we're the first
        case other => other.asInstanceOf[Ref[A]]
      }
    }
  }
}

private final class RefArrayRef[A](
  array: RefArrayBase[A],
  logicalIdx: Int,
) extends UnsealedRef[A] with MemoryLocation[A] {

  private[this] final def itemIdx: Int =
    (3 * this.logicalIdx) + 1

  private[this] final def markerIdx: Int =
    (3 * this.logicalIdx) + 2

  final override def unsafeGetVolatile(): A =
    array.getVolatile(itemIdx).asInstanceOf[A]

  final override def unsafeGetPlain(): A =
    array.getPlain(itemIdx).asInstanceOf[A]

  final override def unsafeSetVolatile(nv: A): Unit =
    array.setVolatile(itemIdx, nv)

  final override def unsafeSetPlain(nv: A): Unit =
    array.setPlain(itemIdx, nv)

  final override def unsafeCasVolatile(ov: A, nv: A): Boolean =
    array.casVolatile(itemIdx, ov, nv)

  final override def unsafeCmpxchgVolatile(ov: A, nv: A): A =
    array.cmpxchgVolatile(itemIdx, ov, nv).asInstanceOf[A]

  final override def unsafeGetVersionVolatile(): Long =
    array.getVersionVolatile(logicalIdx)

  final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long =
    array.cmpxchgVersionVolatile(logicalIdx, ov, nv)

  final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
    array.getVolatile(markerIdx).asInstanceOf[WeakReference[AnyRef]]

  final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    array.casVolatile(markerIdx, ov, nv)

  final override def id0: Long =
    array.id0

  final override def id1: Long =
    array.id1

  final override def id2: Long =
    array.id2

  final override def id3: Long =
    array.id3 | this.logicalIdx.toLong

  final override def toString: String =
    refs.refStringFromIdsAndIdx(id0, id1, id2, id3, this.logicalIdx)

  private[choam] final override def dummy(v: Long): Long =
    v ^ id2
}
