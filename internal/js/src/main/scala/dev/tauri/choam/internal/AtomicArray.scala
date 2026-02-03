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

private[choam] object AtomicArray {

  private[this] final def checkIndex(arr: Array[_], idx: Int): Unit = {
    // Out-of-bounds array indexing is undefined behavior(??) on scala-js,
    // so we need this extra check here (on the JVM, we rely on arrays working):
    if ((idx < 0) || (idx >= arr.length)) {
      throw new IndexOutOfBoundsException(s"Index ${idx} out of bounds for length ${arr.length}")
    }
  }

  // Long:

  final def getVolatile(arr: Array[Long], idx: Int): Long = {
    checkIndex(arr, idx)
    arr(idx)
  }

  final def compareAndExchange(arr: Array[Long], idx: Int, ov: Long, nv: Long): Long = {
    checkIndex(arr, idx)
    val wit = arr(idx)
    if (wit == ov) {
      arr(idx) = nv
    }
    wit
  }

  // AnyRef:

  final def getVolatile(arr: Array[AnyRef], idx: Int): AnyRef = {
    checkIndex(arr, idx)
    arr(idx)
  }

  final def getOpaque(arr: Array[AnyRef], idx: Int): AnyRef = {
    getVolatile(arr, idx)
  }

  final def setVolatile(arr: Array[AnyRef], idx: Int, nv: AnyRef): Unit = {
    checkIndex(arr, idx)
    arr(idx) = nv
  }

  final def compareAndSet(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): Boolean = {
    checkIndex(arr, idx)
    val wit = arr(idx)
    if (wit eq ov) {
      arr(idx) = nv
      true
    } else {
      false
    }
  }

  final def compareAndExchange(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    checkIndex(arr, idx)
    val wit = arr(idx)
    if (wit eq ov) {
      arr(idx) = nv
    }
    wit
  }

  final def compareAndExchangeRel(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    compareAndExchange(arr, idx, ov, nv)
  }

  final def compareAndExchangeOpaque(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    compareAndExchange(arr, idx, ov, nv)
  }
}
