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

import mcas.SimpleMemoryLocation
import java.util.concurrent.atomic.AtomicReference
import java.lang.ref.WeakReference

private final class SingleThreadedRefImpl[A](initial: A)(
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Long,
) extends SimpleMemoryLocation[A](initial)(i0, i1, i2, i3)
  with Ref[A] {

  final override def toString: String =
    refStringFrom4Ids(id0, id1, id2, id3)

  private[choam] final override def dummy(v: Long): Long =
    id0 ^ id1 ^ id2 ^ id3 ^ v

  final override val unsafeWeakMarker: AtomicReference[WeakReference[AnyRef]] =
    null
}
