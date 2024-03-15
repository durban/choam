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

private class SimpleMemoryLocation[A](initial: A)(
  override val id: Long,
) extends AtomicReference[A](initial)
  with MemoryLocation[A] {

  private[this] val version: AtomicLong =
    new AtomicLong(Version.Start)

  private[this] val weakMarker: AtomicReference[WeakReference[AnyRef]] =
    new AtomicReference // (null)

  final override def toString: String =
    "SMemLoc@" + refHashString(id)

  final override def unsafeGetVolatile(): A =
    this.get()

  final override def unsafeGetPlain(): A =
    this.getPlain()

  final override def unsafeSetVolatile(nv: A): Unit =
    this.set(nv)

  final override def unsafeSetPlain(nv: A): Unit =
    this.setPlain(nv)

  final override def unsafeCasVolatile(ov: A, nv: A): Boolean =
    this.compareAndSet(ov, nv)

  final override def unsafeCmpxchgVolatile(ov: A, nv: A): A =
    this.compareAndExchange(ov, nv)

  final override def unsafeGetVersionVolatile(): Long =
    this.version.get()

  final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long =
    this.version.compareAndExchange(ov, nv)

  final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
    this.weakMarker.get()

  final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    this.weakMarker.compareAndSet(ov, nv)
}
