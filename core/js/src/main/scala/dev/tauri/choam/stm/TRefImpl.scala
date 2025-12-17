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
package stm

import java.lang.ref.WeakReference

import scala.collection.mutable.{ LongMap => MutLongMap }
import scala.runtime.LongRef

import internal.mcas.{ Mcas, MemoryLocation, Consts }

private final class TRefImpl[A](
  initial: A,
  final override val id: Long,
) extends core.RefGetAxn[A]
  with core.UnsealedRef[A]
  with SinglethreadedTRefImpl[A]
  with TRefImplBase[A] {

  private[this] var contents: A =
    initial

  private[this] var version: Long =
    internal.mcas.Version.Start

  private[this] val listeners: MutLongMap[Null => Unit] =
    MutLongMap.empty[Null => Unit]

  private[this] val previousListenerId: LongRef =
    LongRef.create(Consts.InvalidListenerId)

  final override def unsafeGetV(): A =
    contents

  final override def unsafeGetP(): A =
    contents

  final override def unsafeSetV(nv: A): Unit =
    contents = nv

  final override def unsafeSetP(nv: A): Unit =
    contents = nv

  final override def unsafeCasV(ov: A, nv: A): Boolean = {
    if (equ(contents, ov)) {
      contents = nv
      true
    } else {
      false
    }
  }

  final override def unsafeCmpxchgV(ov: A, nv: A): A = {
    val wit = contents
    if (equ(wit, ov)) {
      contents = nv
    }
    wit
  }

  final override def unsafeCmpxchgR(ov: A, nv: A): A =
    this.unsafeCmpxchgV(ov, nv)

  final override def unsafeGetVersionV(): Long =
    version

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long = {
    if (version == ov) {
      version = nv
      ov
    } else {
      version
    }
  }

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    impossible("TRefImpl#unsafeGetMarkerV called on JS")

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    impossible("TRefImpl#unsafeCasMarkerV called on JS")

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    impossible("TRefImpl#unsafeCmpxchgMarkerR called on JS")

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

  @inline
  private[choam] final override def unsafeRegisterListener(
    ctx: Mcas.ThreadContext,
    listener: Null => Unit,
    lastSeenVersion: Long,
  ): Long = {
    this.unsafeRegisterListenerImpl(this.listeners, this.previousListenerId, ctx, listener, lastSeenVersion)
  }

  @inline
  private[choam] final override def unsafeCancelListener(lid: Long): Unit = {
    this.unsafeCancelListenerImpl(this.listeners, lid)
  }

  @inline
  private[choam] final override def unsafeNumberOfListeners(): Int = {
    this.unsafeNumberOfListenersImpl(this.listeners)
  }

  @inline
  private[choam] final override def unsafeNotifyListeners(): Unit = {
    this.unsafeNotifyListenersImpl(this.listeners)
  }
}

private[choam] trait SinglethreadedTRefImpl[A]
  extends MemoryLocation[A]
  with MemoryLocation.WithListeners {

  private[choam] final def unsafeRegisterListenerImpl(
    listeners: MutLongMap[Null => Unit],
    previousListenerId: LongRef,
    ctx: Mcas.ThreadContext,
    listener: Null => Unit,
    lastSeenVersion: Long,
  ): Long = {
    val lid = previousListenerId.elem + 1L
    previousListenerId.elem = lid
    Predef.assert(lid != Consts.InvalidListenerId) // detect overflow

    val currVer = ctx.readVersion(this)
    if (currVer != lastSeenVersion) {
      Consts.InvalidListenerId
    } else {
      listeners.put(lid, listener) : Unit
      lid
    }
  }

  private[choam] final def unsafeCancelListenerImpl(listeners: MutLongMap[Null => Unit], lid: Long): Unit = {
    _assert(lid != Consts.InvalidListenerId)
    listeners.remove(lid) : Unit
  }

  private[choam] final def unsafeNumberOfListenersImpl(listeners: MutLongMap[Null => Unit]): Int = {
    listeners.size
  }

  private[choam] final def unsafeNotifyListenersImpl(listeners: MutLongMap[Null => Unit]): Unit = {
    val itr = listeners.valuesIterator
    while (itr.hasNext) {
      val cb = itr.next()
      cb(null)
    }
    listeners.clear()
  }
}