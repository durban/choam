/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }

private final class SimpleMemoryLocation[A](initial: A)(
  override val id: Long,
) extends AtomicReference[A](initial)
  with MemoryLocation[A] {

  private[this] val version: AtomicLong =
    new AtomicLong(Version.Start)

  private[this] val weakMarker: AtomicReference[WeakReference[AnyRef]] =
    new AtomicReference // (null)

  final override def toString: String =
    "SMemLoc@" + refHashString(id)

  final override def hashCode: Int = {
    // `RefIdGen` generates IDs with
    // Fibonacci hashing, so no need
    // to hash them here even further.
    // IDs are globally unique, so the
    // default `equals` (based on object
    // identity) is fine for us.
    this.id.toInt
  }

  final override def unsafeGetV(): A =
    this.get()

  final override def unsafeGetP(): A =
    this.getPlain()

  final override def unsafeSetV(nv: A): Unit =
    this.set(nv)

  final override def unsafeSetP(nv: A): Unit =
    this.setPlain(nv)

  final override def unsafeCasV(ov: A, nv: A): Boolean =
    this.compareAndSet(ov, nv)

  final override def unsafeCmpxchgV(ov: A, nv: A): A =
    this.compareAndExchange(ov, nv)

  final override def unsafeGetVersionV(): Long =
    this.version.get()

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long =
    this.version.compareAndExchange(ov, nv)

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    this.weakMarker.get()

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    this.weakMarker.compareAndSet(ov, nv)
}
