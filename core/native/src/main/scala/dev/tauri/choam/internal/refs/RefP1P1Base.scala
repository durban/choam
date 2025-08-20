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

import scala.scalanative.annotation.alwaysinline

import core.{ Ref, UnsealedRef2 }
import mcas.Version

private abstract class RefP1P1Base[A, B](a: A, i0: Long)
  extends RefIdAndPaddingN(i0)
  with UnsealedRef2[A, B]
  with Ref2ImplBase[A, B] {

  @volatile
  private[this] var valueA: A =
    a

  @volatile
  private[this] var versionA: Long =
    Version.Start

  @volatile
  private[this] var markerA: WeakReference[AnyRef] =
    _

  @alwaysinline
  private[this] final def atomicValueA: AtomicHandle[A] = {
    AtomicHandle(this, "valueA")
  }

  @alwaysinline
  private[this] final def atomicVersionA: AtomicLongHandle = {
    AtomicLongHandle(this, "versionA")
  }

  @alwaysinline
  private[this] final def atomicMarkerA: AtomicHandle[WeakReference[AnyRef]] = {
    AtomicHandle(this, "markerA")
  }

  private[this] val refA: Ref[A] =
    new Ref2Ref1[A, B](this)

  final override def _1: Ref[A] =
    this.refA

  final override def unsafeGet1V(): A =
    this.valueA

  final override def unsafeGet1P(): A =
    atomicValueA.getOpaque // TODO: plain

  final override def unsafeSet1V(a: A): Unit = {
    this.valueA = a
  }

  final override def unsafeSet1P(a: A): Unit = {
    atomicValueA.setOpaque(a) // TODO: plain
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

  final override def id0(): Long = {
    this.id
  }
}
