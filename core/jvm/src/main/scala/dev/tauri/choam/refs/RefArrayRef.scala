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

import internal.mcas.{ MemoryLocation, RefIdGen }

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

  final override def id: Long = { // TODO: maybe make this a val? (time vs space)
    RefIdGen.compute(array.idBase, this.logicalIdx)
  }

  final override def toString: String =
    refs.refArrayRefToString(array.idBase, this.logicalIdx)

  private[choam] final override def dummy(v: Long): Long =
    v ^ id
}
