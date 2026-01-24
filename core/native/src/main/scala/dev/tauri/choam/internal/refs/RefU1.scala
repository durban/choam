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

import core.UnsealedRef
import mcas.{ MemoryLocation, Version }

private final class RefU1[A](
  @volatile private[this] var value: A,
  i: Long,
) extends RefIdOnly[A](i) with UnsealedRef[A] with MemoryLocation[A] {

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var version: Long =
    Version.Start

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var marker: WeakReference[AnyRef] =
    _

  @alwaysinline
  private[this] final def atomicValue: AtomicHandle[A] = {
    AtomicHandle(this, "value")
  }

  @alwaysinline
  private[this] final def atomicVersion: AtomicLongHandle = {
    AtomicLongHandle(this, "version")
  }

  @alwaysinline
  private[this] final def atomicMarker: AtomicHandle[WeakReference[AnyRef]] = {
    AtomicHandle(this, "marker")
  }

  protected[this] final override def refToString(): String = {
    refStringFrom1(this.id)
  }

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean = {
    atomicMarker.compareAndSet(ov, nv)
  }

  final override def unsafeCasV(ov: A, nv: A): Boolean = {
    atomicValue.compareAndSet(ov, nv)
  }

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] = {
    atomicMarker.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }
  final override def unsafeCmpxchgR(ov: A, nv: A): A = {
    atomicValue.compareAndExchangeRelAcq(ov, nv) // TODO: release-only
  }

  final override def unsafeCmpxchgV(ov: A, nv: A): A = {
    atomicValue.compareAndExchange(ov, nv)
  }

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long = {
    atomicVersion.compareAndExchange(ov, nv)
  }

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] = {
    this.marker
  }

  final override def unsafeGetP(): A = {
    atomicValue.getOpaque // TODO: plain
  }

  final override def unsafeGetV(): A = {
    this.value
  }

  final override def unsafeGetVersionV(): Long = {
    this.version
  }

  final override def unsafeSetP(nv: A): Unit = {
    atomicValue.setOpaque(nv) // TODO: plain
  }

  final override def unsafeSetV(nv: A): Unit = {
    this.value = nv
  }

  private[choam] def dummy(v: Byte): Long = {
    42L
  }
}
