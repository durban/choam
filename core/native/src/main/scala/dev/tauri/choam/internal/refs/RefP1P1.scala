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

import core.Ref

private final class RefP1P1[A, B](
  a: A,
  b: B,
  i0: Long,
  i1: Long,
) extends PaddingForP1P1[A, B](a, i0) with Ref2Impl[A, B] {

  private[this] val refB: Ref[B] =
    new Ref2Ref2[A, B](this)

  final override def _2: Ref[B] =
    this.refB

  protected[this] final override def refToString(): String = {
    return refStringFrom2(this.id0(), this.id1())
  }

  final override def dummyImpl1(v: Byte): Long = {
    this.dummyImpl(v.toLong)
  }

  final override def dummyImpl2(v: Byte): Long = ???
  final override def id1(): Long = ???
  final override def unsafeCas2V(ov: B, nv: B): Boolean = ???
  final override def unsafeCasMarker2V(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean = ???
  final override def unsafeCmpxchg2R(ov: B, nv: B): B = ???
  final override def unsafeCmpxchg2V(ov: B, nv: B): B = ???
  final override def unsafeCmpxchgMarker2R(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] = ???
  final override def unsafeCmpxchgVersion2V(ov: Long, nv: Long): Long = ???
  final override def unsafeGet2P(): B = ???
  final override def unsafeGet2V(): B = ???
  final override def unsafeGetMarker2V(): WeakReference[AnyRef] = ???
  final override def unsafeGetVersion2V(): Long = ???
  final override def unsafeSet2P(nv: B): Unit = ???
  final override def unsafeSet2V(nv: B): Unit = ???
}
