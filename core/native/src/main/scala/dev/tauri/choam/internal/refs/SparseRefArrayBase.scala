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
package internal
package refs

import scala.scalanative.annotation.alwaysinline

private abstract class SparseRefArrayBase[A](
  size: Int,
  init: AnyRef,
  idBase: Long,
) extends RefArrayBase[A](size, init, idBase, sparse = true) {

  private[this] var versions: Array[Long] =
    null

  @alwaysinline
  private[this] final def atomicVersions: AtomicHandle[Array[Long]] =
    AtomicHandle(this, "versions")

  protected[refs] final override def getVersionV(idx: Int): Long = ???

  protected[refs] final override def cmpxchgVersionV(idx: Int, ov: Long, nv: Long): Long = ???

  private[this] final def getOrInitVersions(): Array[Long] = {
    val vers = atomicVersions.getAcquire
    if (vers eq null) {
      val candidate = RefArrayBase.initVersions(this._size)
      val wit = atomicVersions.compareAndExchangeRelAcq(null, candidate)
      if (wit eq null) {
        candidate
      } else {
        wit
      }
    } else {
      vers
    }
  }
}
