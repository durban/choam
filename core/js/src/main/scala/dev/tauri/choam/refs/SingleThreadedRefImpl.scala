/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicReference

import mcas.MemoryLocation

private final class SingleThreadedRefImpl[A](initial: A)(
  final override val id0: Long,
  final override val id1: Long,
  final override val id2: Long,
  final override val id3: Long,
) extends Ref[A]
  with MemoryLocation[A] {

  private[this] val repr =
    new AtomicReference[A](initial)

  final override def unsafeGetVolatile(): A =
    repr.get()

  final override def unsafeSetVolatile(nv: A): Unit =
    repr.set(nv)

  final override def unsafeCasVolatile(ov: A, nv: A): Boolean =
    repr.compareAndSet(ov, nv)

  final override def unsafeCmpxchgVolatile(ov: A, nv: A): A =
    repr.compareAndExchange(ov, nv)

  final override def toString: String =
    refStringFrom4Ids(id0, id1, id2, id3)

  private[choam] final override def dummy(v: Long): Long =
    id0 ^ id1 ^ id2 ^ id3 ^ v
}
