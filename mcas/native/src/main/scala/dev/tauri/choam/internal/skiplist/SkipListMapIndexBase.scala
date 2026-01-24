/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package skiplist

import scala.scalanative.annotation.alwaysinline

private[skiplist] abstract class SkipListMapIndexBase[I <: SkipListMapIndexBase[I]] protected[this] (
  // plain writes are fine here, since
  // new `Index`es are alway published
  // with a volatile-CAS:
  private[this] var right: I,
) {

  @alwaysinline
  private[this] final def atomicRight: AtomicHandle[I] = {
    AtomicHandle(this, "right")
  }

  final def getRight(): I = {
    atomicRight.getAcquire
  }

  final def setRightP(nv: I): Unit = {
    this.right = nv
  }

  final def casRight(ov: I, nv: I): Boolean = {
    atomicRight.compareAndSet(ov, nv)
  }
}
