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

import scala.scalanative.unsafe.{ UnsafeRichArray, Ptr, stackalloc }
import scala.scalanative.libc.stdatomic.{
  atomic_compare_exchange_strong_explicit,
  atomic_load_explicit,
  atomic_store_explicit,
  memory_order,
}
import scala.scalanative.libc.stdatomic.memory_order.{
  memory_order_seq_cst,
  memory_order_acquire,
  memory_order_relaxed,
  memory_order_release,
}

private[choam] object AtomicArray {

  // Long:

  final def getVolatile(arr: Array[Long], idx: Int): Long = {
    val ptr: Ptr[Long] = arr.at(idx)
    atomic_load_explicit(ptr, memory_order_seq_cst)
  }

  final def compareAndExchange(arr: Array[Long], idx: Int, ov: Long, nv: Long): Long = {
    val ptr: Ptr[Long] = arr.at(idx)
    val expected: Ptr[Long] = stackalloc[Long]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, memory_order_seq_cst, memory_order_acquire) : Unit
    !expected
  }

  // AnyRef:

  final def getVolatile(arr: Array[AnyRef], idx: Int): AnyRef = {
    val ptr: Ptr[AnyRef] = arr.at(idx)
    atomic_load_explicit(ptr, memory_order_seq_cst)
  }

  final def getOpaque(arr: Array[AnyRef], idx: Int): AnyRef = {
    val ptr: Ptr[AnyRef] = arr.at(idx)
    atomic_load_explicit(ptr, memory_order_relaxed)
  }

  final def setVolatile(arr: Array[AnyRef], idx: Int, nv: AnyRef): Unit = {
    val ptr: Ptr[AnyRef] = arr.at(idx)
    atomic_store_explicit(ptr, nv, memory_order_seq_cst)
  }

  final def compareAndSet(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): Boolean = {
    val ptr: Ptr[AnyRef] = arr.at(idx)
    val expected: Ptr[AnyRef] = stackalloc[AnyRef]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, memory_order_seq_cst, memory_order_acquire)
  }

  final def compareAndExchange(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    _cmpxchg(arr, idx, ov, nv, memory_order_seq_cst, memory_order_acquire)
  }

  final def compareAndExchangeRel(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    _cmpxchg(arr, idx, ov, nv, memory_order_release, memory_order_relaxed)
  }

  final def compareAndExchangeOpaque(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef): AnyRef = {
    _cmpxchg(arr, idx, ov, nv, memory_order_relaxed, memory_order_relaxed)
  }

  private[this] final def _cmpxchg(arr: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef, moSuccess: memory_order, moFailure: memory_order): AnyRef = {
    val ptr: Ptr[AnyRef] = arr.at(idx)
    val expected: Ptr[AnyRef] = stackalloc[AnyRef]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, moSuccess, moFailure) : Unit
    !expected
  }
}
