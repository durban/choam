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
package stm

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }

import internal.mcas.MemoryLocation

private final class TRefImpl[F[_], A](
  initial: A,
  final override val id: Long,
) extends MemoryLocation[A] with TRef.UnsealedTRef[F, A] {

  // TODO: use VarHandles

  private[this] val contents =
    new AtomicReference[A](initial)

  private[this] val version =
    new AtomicLong(internal.mcas.Version.Start)

  private[this] val marker =
    new AtomicReference[WeakReference[AnyRef]]

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
    core.Rxn.loc.get(this).castF[F]

  final override def set(a: A): Txn[F, Unit] =
    core.Rxn.loc.upd[A, Unit, Unit](this) { (_, _) => (a, ()) }.castF[F]
}
