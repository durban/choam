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
package refs

import java.lang.ref.WeakReference

import core.UnsealedRef
import mcas.{ MemoryLocation, RefIdGen }

private final class RefArrayRef[A](
  array: RefArrayBase[A],
  logicalIdx: Int,
) extends core.RefGetAxn[A] with UnsealedRef[A] with MemoryLocation[A] {

  final override val id: Long =
    RefIdGen.compute(array.idBase, logicalIdx)

  final override def hashCode: Int =
    this.id.toInt

  private[this] final def itemIdx: Int =
    (3 * this.logicalIdx) + 1

  private[this] final def markerIdx: Int =
    (3 * this.logicalIdx) + 2

  final override def unsafeGetV(): A =
    array.getV(itemIdx).asInstanceOf[A]

  final override def unsafeGetP(): A =
    array.getP(itemIdx).asInstanceOf[A]

  final override def unsafeSetV(nv: A): Unit =
    array.setV(itemIdx, nv)

  final override def unsafeSetP(nv: A): Unit =
    array.setP(itemIdx, nv)

  final override def unsafeCasV(ov: A, nv: A): Boolean =
    array.casV(itemIdx, ov, nv)

  final override def unsafeCmpxchgV(ov: A, nv: A): A =
    array.cmpxchgV(itemIdx, ov, nv).asInstanceOf[A]

  final override def unsafeCmpxchgR(ov: A, nv: A): A =
    array.cmpxchgR(itemIdx, ov, nv).asInstanceOf[A]

  final override def unsafeGetVersionV(): Long =
    array.getVersionV(logicalIdx)

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long =
    array.cmpxchgVersionV(logicalIdx, ov, nv)

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    array.getV(markerIdx).asInstanceOf[WeakReference[AnyRef]]

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    array.casV(markerIdx, ov, nv)

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    array.cmpxchgR(markerIdx, ov, nv).asInstanceOf[WeakReference[AnyRef]]

  final override def toString: String =
    refs.refArrayRefToString(array.idBase, this.logicalIdx)

  private[choam] final override def dummy(v: Byte): Long =
    v ^ id
}
