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
package refs

private final class SparseRefArray[A](
  __size: Int,
  initial: A,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int, // LSB is array index
) extends SparseRefArrayBase[A](__size, initial, i0, i1, i2, i3.toLong << 32)
  with Ref.UnsealedArray[A] {

  require((__size > 0) && (((__size - 1) * 3 + 2) > (__size - 1))) // avoid overflow

  final override def size: Int =
    this._size

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeGet(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < size)) {
      this.getOrCreateRef(idx)
    } else {
      null
    }
  }

  private[this] final def getOrCreateRef(i: Int): Ref[A] = {
    val refIdx = 3 * i
    val existing = this.getOpaque(refIdx)
    if (existing ne null) {
      // `RefArrayRef` has only final fields,
      // so it's "safely initialized", so if
      // we've read something here with `getOpaque`,
      // then we also can see its fields:
      existing.asInstanceOf[Ref[A]]
    } else {
      val nv = new RefArrayRef[A](this, i)
      this.cmpxchgVolatile(refIdx, null, nv) match { // TODO: <- we don't need volatile!
        case null => nv // we're the first
        case other => other.asInstanceOf[Ref[A]]
      }
    }
  }
}
