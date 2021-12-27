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
package mcas

import java.util.concurrent.atomic.AtomicReference

private[choam] abstract class SimpleMemoryLocation[A](initial: A)(
  override val id0: Long,
  override val id1: Long,
  override val id2: Long,
  override val id3: Long,
) extends AtomicReference[A](initial)
  with MemoryLocation[A] {

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
}
