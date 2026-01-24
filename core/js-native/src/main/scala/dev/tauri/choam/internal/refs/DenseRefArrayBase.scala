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
package refs

private abstract class DenseRefArrayBase[A](
  size: Int, init: AnyRef, idBase: Long
) extends RefArrayBase[A](size, init, idBase, sparse = false) {

  private[this] val versions: Array[Long] =
    RefArrayBase.initVersions(size)

  protected[refs] final override def getVersionV(idx: Int): Long = {
    AtomicArray.getVolatile(versions, idx)
  }

  protected[refs] final override def cmpxchgVersionV(idx: Int, ov: Long, nv: Long): Long = {
    AtomicArray.compareAndExchange(versions, idx, ov, nv)
  }
}
