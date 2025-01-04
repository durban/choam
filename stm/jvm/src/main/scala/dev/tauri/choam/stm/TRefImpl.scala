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
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }

import scala.collection.immutable.LongMap

import internal.mcas.{ Mcas, MemoryLocation, Consts }

private final class TRefImpl[F[_], A](
  initial: A,
  final override val id: Long,
) extends core.RefGetAxn[A]
  with MemoryLocation[A]
  with MemoryLocation.WithListeners
  with TRef.UnsealedTRef[F, A] {

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
    contents.getPlain()

  final override def unsafeSetV(nv: A): Unit =
    contents.set(nv)

  final override def unsafeSetP(nv: A): Unit =
    contents.setPlain(nv)

  final override def unsafeCasV(ov: A, nv: A): Boolean =
    contents.compareAndSet(ov, nv)

  final override def unsafeCmpxchgV(ov: A, nv: A): A =
    contents.compareAndExchange(ov, nv)

  final override def unsafeCmpxchgR(ov: A, nv: A): A =
    contents.compareAndExchangeRelease(ov, nv)

  final override def unsafeGetVersionV(): Long =
    version.get()

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long =
    version.compareAndExchange(ov, nv)

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    marker.get()

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    marker.compareAndSet(ov, nv)

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    marker.compareAndExchangeRelease(ov, nv)

  final override def get: Txn[F, A] =
    this.castF[F]

  final override def set(a: A): Txn[F, Unit] =
    core.Rxn.loc.upd[A, Unit, Unit](this) { (_, _) => (a, ()) }.castF[F]

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
    "TRef@" + internal.mcas.refHashString(this.id)

  private[choam] final override def withListeners: this.type =
    this

  private[choam] final override def unsafeRegisterListener(
    ctx: Mcas.ThreadContext,
    listener: Null => Unit,
    lastSeenVersion: Long,
  ): Long = {
    val lid = previousListenerId.incrementAndGet() // could be opaque
    Predef.assert(lid != Consts.InvalidListenerId) // detect overflow

    @tailrec
    def go(ov: LongMap[Null => Unit]): Unit = {
      val nv = ov.updated(lid, listener)
      val wit = listeners.compareAndExchange(ov, nv)
      if (wit ne ov) {
        go(wit)
      }
    }

    go(listeners.get())
    val currVer = ctx.readVersion(this)
    if (currVer != lastSeenVersion) {
      // already changed since our caller last seen it
      // (it is possible that the callback will be called
      // anyway, since there is a race between double-
      // checking the version and a possible notification;
      // it is the responsibility of the caller to check
      // the return value of this method, and ignore calls
      // to the callback if we return `InvalidListenerId`)
      unsafeCancelListener(lid)
      Consts.InvalidListenerId
    } else {
      lid
    }
  }

  private[choam] final override def unsafeCancelListener(lid: Long): Unit = {
    _assert(lid != Consts.InvalidListenerId)

    @tailrec
    def go(ov: LongMap[Null => Unit]): Unit = {
      val nv = ov.removed(lid)
      if (nv ne ov) {
        val wit = listeners.compareAndExchange(ov, nv)
        if (wit ne ov) {
          go(wit)
        } // else: we're done
      } // else: we're done
    }

    go(listeners.get())
  }

  private[choam] final override def unsafeNotifyListeners(): Unit = {
    // TODO: If there are A LOT of listeners, calling all
    // TODO: these async callbacks could take a while;
    // TODO: we should consider passing these off to an
    // TODO: execution context (how?).
    val lss = listeners.getAndSet(LongMap.empty)
    val itr = lss.valuesIterator
    while (itr.hasNext) {
      val cb = itr.next()
      cb(null)
    }
  }
}
