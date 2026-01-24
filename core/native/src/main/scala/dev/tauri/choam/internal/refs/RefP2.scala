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

import core.{ Ref, UnsealedRef2 }
import mcas.Version

private final class RefP2[A, B](
  a: A,
  b: B,
  i0: Long,
  i1: Long,
) extends RefIdAndPaddingN(i0)
  with UnsealedRef2[A, B]
  with Ref2ImplBase[A, B]
  with Ref2Impl[A, B] {

  @volatile
  private[this] var valueA: A =
    a

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var versionA: Long =
    Version.Start

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var markerA: WeakReference[AnyRef] =
    _

  private[this] val refA: Ref[A] =
    new Ref2Ref1[A, B](this)

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
  private[this] final def atomicValueA: AtomicHandle[A] = {
    AtomicHandle(this, "valueA")
  }

  @alwaysinline
  private[this] final def atomicValueB: AtomicHandle[B] = {
    AtomicHandle(this, "valueB")
  }

  @alwaysinline
  private[this] final def atomicVersionA: AtomicLongHandle = {
    AtomicLongHandle(this, "versionA")
  }

  @alwaysinline
  private[this] final def atomicVersionB: AtomicLongHandle = {
    AtomicLongHandle(this, "versionB")
  }

  @alwaysinline
  private[this] final def atomicMarkerA: AtomicHandle[WeakReference[AnyRef]] = {
    AtomicHandle(this, "markerA")
  }

  @alwaysinline
  private[this] final def atomicMarkerB: AtomicHandle[WeakReference[AnyRef]] = {
    AtomicHandle(this, "markerB")
  }

  protected[this] final override def refToString(): String = {
    refStringFrom2(this.id0(), this.id1())
  }

  final override def _1: Ref[A] = {
    this.refA
  }

  final override def _2: Ref[B] = {
    this.refB
  }

  final override def unsafeGet1V(): A = {
    this.valueA
  }

  final override def unsafeGet1P(): A = {
    atomicValueA.getOpaque // TODO: plain
  }

  final override def unsafeCas1V(ov: A, nv: A): Boolean = {
    atomicValueA.compareAndSet(ov, nv)
  }

  final override def unsafeCmpxchg1V(ov: A, nv: A): A = {
    atomicValueA.compareAndExchange(ov, nv)
  }

  final override def unsafeCmpxchg1R(ov: A, nv: A): A = {
    atomicValueA.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def unsafeSet1V(nv: A): Unit = {
    this.valueA = nv
  }

  final override def unsafeSet1P(nv: A): Unit = {
    atomicValueA.setOpaque(nv) // TODO: plain
  }

  final override def unsafeGetVersion1V(): Long = {
    this.versionA
  }

  final override def unsafeCmpxchgVersion1V(ov: Long, nv: Long): Long = {
    atomicVersionA.compareAndExchange(ov, nv)
  }

  final override def unsafeGetMarker1V(): WeakReference[AnyRef] = {
    this.markerA
  }

  final override def unsafeCasMarker1V(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean = {
    atomicMarkerA.compareAndSet(ov, nv)
  }

  final override def unsafeCmpxchgMarker1R(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] = {
    atomicMarkerA.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def id0(): Long =
    i0

  final override def id1(): Long =
    i1

  final override def unsafeGet2V(): B = {
    this.valueB
  }

  final override def unsafeGet2P(): B = {
    atomicValueB.getOpaque // TODO: plain
  }

  final override def unsafeCas2V(ov: B, nv: B): Boolean = {
    atomicValueB.compareAndSet(ov, nv)
  }

  final override def unsafeCmpxchg2V(ov: B, nv: B): B = {
    atomicValueB.compareAndExchange(ov, nv)
  }

  final override def unsafeCmpxchg2R(ov: B, nv: B): B = {
    atomicValueB.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def unsafeSet2V(nv: B): Unit = {
    this.valueB = nv
  }

  final override def unsafeSet2P(nv: B): Unit = {
    atomicValueB.setOpaque(nv) // TODO: plain
  }

  final override def unsafeGetVersion2V(): Long = {
    this.versionB
  }

  final override def unsafeCmpxchgVersion2V(ov: Long, nv: Long): Long = {
    atomicVersionB.compareAndExchange(ov, nv)
  }

  final override def unsafeGetMarker2V(): WeakReference[AnyRef] = {
    this.markerB
  }

  final override def unsafeCasMarker2V(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean = {
    atomicMarkerB.compareAndSet(ov, nv)
  }

  final override def unsafeCmpxchgMarker2R(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] = {
    atomicMarkerB.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def dummyImpl1(v: Byte): Long = {
    this.dummyImpl(v.toLong)
  }

  final override def dummyImpl2(v: Byte): Long = {
    this.dummyImpl(v.toLong)
  }
}
