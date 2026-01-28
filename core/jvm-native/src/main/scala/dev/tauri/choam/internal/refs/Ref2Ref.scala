/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
import mcas.MemoryLocation

private final class Ref2Ref1[A, B](self: Ref2ImplBase[A, B])
  extends core.RefGetAxn[A]
  with UnsealedRef[A]
  with MemoryLocation[A] {

  override def unsafeGetV(): A =
    self.unsafeGet1V()

  override def unsafeGetP(): A =
    self.unsafeGet1P()

  override def unsafeCasV(ov: A, nv: A): Boolean =
    self.unsafeCas1V(ov, nv)

  override def unsafeCmpxchgV(ov: A, nv: A): A =
    self.unsafeCmpxchg1V(ov, nv)

  override def unsafeCmpxchgR(ov: A, nv: A): A =
    self.unsafeCmpxchg1R(ov, nv)

  override def unsafeSetV(a: A): Unit =
    self.unsafeSet1V(a)

  override def unsafeSetP(a: A): Unit =
    self.unsafeSet1P(a)

  final override def unsafeGetVersionV(): Long =
    self.unsafeGetVersion1V()

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long =
    self.unsafeCmpxchgVersion1V(ov, nv)

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    self.unsafeGetMarker1V()

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    self.unsafeCasMarker1V(ov, nv)

  final override def unsafeCmpxchgMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    self.unsafeCmpxchgMarker1V(ov, nv)

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    self.unsafeCmpxchgMarker1R(ov, nv)

  override def id: Long =
    self.id0()

  final override def hashCode: Int =
    this.id.toInt

  final override def toString: String =
    refStringFrom1(this.id)

  final override def dummy(v: Byte): Long =
    self.dummyImpl1(v)
}

private final class Ref2Ref2[A, B](self: Ref2Impl[A, B])
  extends core.RefGetAxn[B]
  with UnsealedRef[B]
  with MemoryLocation[B] {

  override def unsafeGetV(): B =
    self.unsafeGet2V()

  override def unsafeGetP(): B =
    self.unsafeGet2P()

  override def unsafeCasV(ov: B, nv: B): Boolean =
    self.unsafeCas2V(ov, nv)

  override def unsafeCmpxchgV(ov: B, nv: B): B =
    self.unsafeCmpxchg2V(ov, nv)

  override def unsafeCmpxchgR(ov: B, nv: B): B =
    self.unsafeCmpxchg2R(ov, nv)

  override def unsafeSetV(b: B): Unit =
    self.unsafeSet2V(b)

  override def unsafeSetP(b: B): Unit =
    self.unsafeSet2P(b)

  final override def unsafeGetVersionV(): Long =
    self.unsafeGetVersion2V()

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long =
    self.unsafeCmpxchgVersion2V(ov, nv)

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    self.unsafeGetMarker2V()

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    self.unsafeCasMarker2V(ov, nv)

  final override def unsafeCmpxchgMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    self.unsafeCmpxchgMarker2V(ov, nv)

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    self.unsafeCmpxchgMarker2R(ov, nv)

  override def id: Long =
    self.id1()

  final override def hashCode: Int =
    this.id.toInt

  final override def toString: String =
    refStringFrom1(this.id)

  override def dummy(v: Byte): Long =
    self.dummyImpl2(v)
}
