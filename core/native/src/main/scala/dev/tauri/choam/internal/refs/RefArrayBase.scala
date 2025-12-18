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

import mcas.Version

private object RefArrayBase {

  final def initVersions(size: Int): Array[Long] = {
    val vers = new Array[Long](size)
    var i = 0
    while (i < size) {
      vers(i) = Version.Start
      i += 1
    }
    vers
  }
}

private abstract class RefArrayBase[A](
  size: Int,
  init: AnyRef,
  _idBase: Long,
  sparse: Boolean,
) extends RefIdOnlyN(_idBase) {

  protected[this] val _size: Int =
    size

  protected[this] def createRef(i: Int): RefArrayRef[A]

  private[this] val array: Array[AnyRef] = {
    val arr = new Array[AnyRef](3 * _size);
    var i = 0
    if (sparse) {
      while (i < size) {
        val itemIdx = (3 * i) + 1
        arr(itemIdx) = init
        // TODO: plain writes, final field
        i += 1
      }
    } else {
      while (i < size) {
        val refIdx = 3 * i
        val itemIdx = refIdx + 1
        arr(refIdx) = new RefArrayRef[A](this, i)
        arr(itemIdx) = init
        // TODO: plain writes, final field
        i += 1
      }
    }
    arr
  }

  final def idBase: Long = {
    this.id
  }

  protected[refs] def getVersionV(idx: Int): Long

  protected[refs] def cmpxchgVersionV(idx: Int, ov: Long, nv: Long): Long

  protected[refs] final def getV(idx: Int): AnyRef = {
    AtomicArray.getVolatile(this.array, idx)
  }

  protected[refs] final def getO(idx: Int): AnyRef = {
    AtomicArray.getOpaque(this.array, idx)
  }

  protected[refs] final def getP(idx: Int): AnyRef = {
    this.array(idx)
  }

  protected[refs] final def setV(idx: Int, nv: AnyRef): Unit = {
    AtomicArray.setVolatile(this.array, idx, nv)
  }

  protected[refs] final def setP(idx: Int, nv: AnyRef): Unit = {
    this.array(idx) = nv
  }

  protected[refs] final def casV(idx: Int, ov: AnyRef, nv: AnyRef): Boolean = {
    AtomicArray.compareAndSet(this.array, idx, ov, nv)
  }

  protected[refs] final def cmpxchgV(idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    AtomicArray.compareAndExchange(this.array, idx, ov, nv)
  }

  protected[refs] final def cmpxchgR(idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    AtomicArray.compareAndExchangeRel(this.array, idx, ov, nv)
  }

  protected[refs] final def cmpxchgO(idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    AtomicArray.compareAndExchangeOpaque(this.array, idx, ov, nv)
  }
}
