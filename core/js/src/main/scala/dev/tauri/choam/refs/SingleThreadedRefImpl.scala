/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import internal.mcas.{ MemoryLocation, Version }

// This is JS:
private final class SingleThreadedRefImpl[A](private[this] var value: A)(
  override val id0: Long,
  override val id1: Long,
  override val id2: Long,
  override val id3: Long,
) extends MemoryLocation[A]
  with Ref[A] {

  final override def toString: String =
    refStringFrom4Ids(id0, id1, id2, id3)

  private[choam] final override def dummy(v: Long): Long =
    id0 ^ id1 ^ id2 ^ id3 ^ v

  private[this] var version: Long =
    Version.Start

  final override def unsafeGetVolatile(): A =
    this.value

  final override def unsafeGetPlain(): A =
    this.value

  final override def unsafeSetVolatile(nv: A): Unit =
    this.value = nv

  final override def unsafeSetPlain(nv: A): Unit =
    this.value = nv

  final override def unsafeCasVolatile(ov: A, nv: A): Boolean = {
    if (equ(this.value, ov)) {
      this.value = nv
      true
    } else {
      false
    }
  }

  final override def unsafeCmpxchgVolatile(ov: A, nv: A): A = {
    val witness = this.value
    if (equ(witness, ov)) {
      this.value = nv
    }
    witness
  }

  final override def unsafeGetVersionVolatile(): Long =
    this.version

  final override def unsafeCasVersionVolatile(ov: Long, nv: Long): Boolean = {
    if (this.version == ov) {
      this.version = nv
      true
    } else {
      false
    }
  }

  final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long = {
    if (this.version == ov) {
      this.version = nv
      ov
    } else {
      this.version
    }
  }

  // These are used only by EMCAS, which is JVM-only:

  final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
    impossible("SingleThreadedRefImpl#unsafeGetMarkerVolatile called on JS")

  final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    impossible("SingleThreadedRefImpl#unsafeCasMarkerVolatile called on JS")
}
