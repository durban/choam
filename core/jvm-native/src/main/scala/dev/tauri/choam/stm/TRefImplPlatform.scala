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

import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }

import scala.collection.immutable.LongMap

import internal.mcas.{ Mcas, MemoryLocation, Consts }

private[choam] trait TRefImplPlatform[A]
  extends MemoryLocation[A]
  with MemoryLocation.WithListeners {

  private[choam] final def unsafeRegisterListenerImpl(
    listeners: AtomicReference[LongMap[Null => Unit]],
    previousListenerId: AtomicLong,
    ctx: Mcas.ThreadContext,
    listener: Null => Unit,
    lastSeenVersion: Long,
  ): Long = {
    val lid = previousListenerId.incrementAndGet() // TODO: could be opaque; use VarHandle#getAndAddAcquire
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
      unsafeCancelListener(lid) // TODO: is this really necessary? (i.e., can we leak without it?)
      Consts.InvalidListenerId
    } else {
      lid
    }
  }

  private[choam] final def unsafeCancelListenerImpl(
    listeners: AtomicReference[LongMap[Null => Unit]],
    lid: Long,
  ): Unit = {
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

  private[choam] final def unsafeNumberOfListenersImpl(
    listeners: AtomicReference[LongMap[Null => Unit]],
  ): Int = {
    listeners.get().size
  }

  private[choam] final def unsafeNotifyListenersImpl(
    listeners: AtomicReference[LongMap[Null => Unit]],
  ): Unit = {
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

  @inline
  protected[this] final def getPlainAr[X](ar: AtomicReference[X]): X = {
    ar.getPlain()
  }

  @inline
  protected[this] final def setPlainAr[X](ar: AtomicReference[X], nv: X): Unit = {
    ar.setPlain(nv)
  }

  @inline
  protected[this] final def cmpxchgAl(al: AtomicLong, ov: Long, nv: Long): Long = {
    al.compareAndExchange(ov, nv)
  }
}
