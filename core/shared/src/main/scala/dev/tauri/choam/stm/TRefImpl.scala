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
package stm

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }

import scala.collection.immutable.LongMap

import internal.mcas.{ Mcas, MemoryLocation, Consts }

private final class TRefImpl[A](
  initial: A,
  final override val id: Long,
) extends core.RefGetAxn[A]
  with core.UnsealedRef[A]
  with MemoryLocation.WithListeners
  with TRefImplBase[A]
  with TRefImplPlatform[A] {

  // TODO: use VarHandles

  private[this] val contents =
    new AtomicReference[A](initial)

  private[this] val version =
    new AtomicLong(internal.mcas.Version.Start)

  private[this] val marker =
    new AtomicReference[WeakReference[AnyRef]]

  private[this] val listeners =
    new AtomicReference[LongMap[Null => Unit]](LongMap.empty)

  private[this] val previousListenerId =
    new AtomicLong(Consts.InvalidListenerId)

  final override def unsafeGetV(): A =
    contents.get()

  final override def unsafeGetP(): A =
    this.getPlainAr(contents)

  final override def unsafeSetV(nv: A): Unit =
    contents.set(nv)

  final override def unsafeSetP(nv: A): Unit =
    this.setPlainAr(contents, nv)

  final override def unsafeCasV(ov: A, nv: A): Boolean =
    contents.compareAndSet(ov, nv)

  final override def unsafeCmpxchgV(ov: A, nv: A): A =
    contents.compareAndExchange(ov, nv)

  final override def unsafeCmpxchgR(ov: A, nv: A): A =
    contents.compareAndExchangeRelease(ov, nv)

  final override def unsafeGetVersionV(): Long =
    version.get()

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long =
    this.cmpxchgAl(version, ov, nv)

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    marker.get()

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    marker.compareAndSet(ov, nv)

  final override def unsafeCmpxchgMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    marker.compareAndExchange(ov, nv)

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    marker.compareAndExchangeRelease(ov, nv)

  private[choam] final override def refImpl: core.Ref[A] =
    this

  final override def hashCode: Int = {
    // `RefIdGen` generates IDs with
    // Fibonacci hashing, so no need
    // to hash them here even further.
    // IDs are globally unique, so the
    // default `equals` (based on object
    // identity) is fine for us.
    this.id.toInt
  }

  final override def toString: String =
    "TRef@" + internal.mcas.refIdHexString(this.id)

  private[choam] final override def withListeners: this.type =
    this

  private[choam] final override def unsafeRegisterListener(
    ctx: Mcas.ThreadContext,
    listener: Null => Unit,
    lastSeenVersion: Long,
  ): Long = {
    this.unsafeRegisterListenerImpl(this.listeners, this.previousListenerId, ctx, listener, lastSeenVersion)
  }

  private[choam] final override def unsafeCancelListener(lid: Long): Unit = {
    this.unsafeCancelListenerImpl(this.listeners, lid)
  }

  private[choam] final override def unsafeNumberOfListeners(): Int = {
    this.unsafeNumberOfListenersImpl(this.listeners)
  }

  private[choam] final override def unsafeNotifyListeners(): Unit = {
    this.unsafeNotifyListenersImpl(this.listeners)
  }
}
