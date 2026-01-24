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

import scala.scalanative.annotation.alwaysinline

import mcas.Version
import core.Ref

private final class RefP1P1[A, B](
  a: A,
  b: B,
  i0: Long,
  i1: Long,
) extends PaddingForP1P1[A, B](a, i0) with Ref2Impl[A, B] {

  private[this] val _id1: Long =
    i1

  @volatile
  private[this] var valueB: B =
    b

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var versionB: Long =
    Version.Start

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var markerB: WeakReference[AnyRef] =
    _

  private[this] val refB: Ref[B] =
    new Ref2Ref2[A, B](this)

  @alwaysinline
  private[this] final def atomicValueB: AtomicHandle[B] = {
    AtomicHandle(this, "valueB")
  }

  @alwaysinline
  private[this] final def atomicVersionB: AtomicLongHandle = {
    AtomicLongHandle(this, "versionB")
  }

  @alwaysinline
  private[this] final def atomicMarkerB: AtomicHandle[WeakReference[AnyRef]] = {
    AtomicHandle(this, "markerB")
  }

  final override def _2: Ref[B] =
    this.refB

  protected[this] final override def refToString(): String = {
    refStringFrom2(this.id0(), this.id1())
  }

  final override def dummyImpl1(v: Byte): Long = {
    this.dummyImpl(v.toLong)
  }

  final override def id1(): Long =
    _id1

  final override def unsafeCas2V(ov: B, nv: B): Boolean = {
    atomicValueB.compareAndSet(ov, nv)
  }

  final override def unsafeCasMarker2V(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean = {
    atomicMarkerB.compareAndSet(ov, nv)
  }

  final override def unsafeCmpxchg2R(ov: B, nv: B): B = {
    atomicValueB.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def unsafeCmpxchg2V(ov: B, nv: B): B = {
    atomicValueB.compareAndExchange(ov, nv)
  }

  final override def unsafeCmpxchgMarker2R(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] = {
    atomicMarkerB.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def unsafeCmpxchgVersion2V(ov: Long, nv: Long): Long = {
    atomicVersionB.compareAndExchange(ov, nv)
  }

  final override def unsafeGet2P(): B = {
    atomicValueB.getOpaque // TODO: plain
  }

  final override def unsafeGet2V(): B = {
    this.valueB
  }

  final override def unsafeGetMarker2V(): WeakReference[AnyRef] = {
    this.markerB
  }

  final override def unsafeGetVersion2V(): Long = {
    this.versionB
  }

  final override def unsafeSet2P(nv: B): Unit = {
    atomicValueB.setOpaque(nv) // TODO: plain
  }

  final override def unsafeSet2V(nv: B): Unit = {
    this.valueB = nv
  }

  final override def dummyImpl2(v: Byte): Long =
    this.dummyImpl(v.toLong)
}
