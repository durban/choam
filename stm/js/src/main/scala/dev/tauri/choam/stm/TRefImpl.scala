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

import internal.mcas.MemoryLocation

private final class TRefImpl[F[_], A](
  initial: A,
  final override val id: Long,
) extends MemoryLocation[A] with TRef.UnsealedTRef[F, A] {

  private[this] var contents: A =
    initial

  private[this] var version: Long =
    internal.mcas.Version.Start

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

  final override def get: Txn[F, A] =
    core.Rxn.loc.get(this).castF[F]

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
}
