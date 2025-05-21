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
package refs

import java.lang.ref.WeakReference

import core.UnsealedRef
import internal.mcas.{ MemoryLocation, Version }

// This is JS:
private final class SingleThreadedRefImpl[A](private[this] var value: A)(
  override val id: Long,
) extends core.RefGetAxn[A]
  with MemoryLocation[A]
  with UnsealedRef[A] {

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
    refStringFrom4Ids(id)

  private[choam] final override def dummy(v: Byte): Long =
    id ^ v

  private[this] var version: Long =
    Version.Start

  final override def unsafeGetV(): A =
    this.value

  final override def unsafeGetP(): A =
    this.value

  final override def unsafeSetV(nv: A): Unit =
    this.value = nv

  final override def unsafeSetP(nv: A): Unit =
    this.value = nv

  final override def unsafeCasV(ov: A, nv: A): Boolean = {
    if (equ(this.value, ov)) {
      this.value = nv
      true
    } else {
      false
    }
  }

  final override def unsafeCmpxchgV(ov: A, nv: A): A = {
    val witness = this.value
    if (equ(witness, ov)) {
      this.value = nv
    }
    witness
  }

  final override def unsafeCmpxchgR(ov: A, nv: A): A = {
    this.unsafeCmpxchgV(ov, nv)
  }

  final override def unsafeGetVersionV(): Long =
    this.version

  final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long = {
    if (this.version == ov) {
      this.version = nv
      ov
    } else {
      this.version
    }
  }

  // These are used only by EMCAS, which is JVM-only:

  final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
    impossible("SingleThreadedRefImpl#unsafeGetMarkerV called on JS")

  final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    impossible("SingleThreadedRefImpl#unsafeCasMarkerV called on JS")

  final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
    impossible("SingleThreadedRefImpl#unsafeCmpxchgMarkerR called on JS")
}
