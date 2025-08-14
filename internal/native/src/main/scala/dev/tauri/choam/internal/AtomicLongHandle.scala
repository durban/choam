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

import scala.scalanative.annotation.alwaysinline
import scala.scalanative.unsafe.{ Ptr, stackalloc }
import scala.scalanative.libc.stdatomic.{
  atomic_llong,
  atomic_load_explicit,
  atomic_store_explicit,
  atomic_compare_exchange_strong_explicit,
  atomic_fetch_add_explicit,
  memory_order,
}
import scala.scalanative.libc.stdatomic.memory_order.{
  memory_order_relaxed,
  memory_order_acquire,
  memory_order_acq_rel,
  memory_order_release,
  memory_order_seq_cst,
}

private[choam] object AtomicLongHandle {

  import scala.language.experimental.macros

  final def apply[O <: AnyRef](obj: O, fieldName: String): AtomicLongHandle =
    macro AtomicHandleMacros.longImpl[O]

  @alwaysinline
  final def newAtomicLongHandleDoNotCallThisMethod[A](ptr: Ptr[atomic_llong]): AtomicLongHandle = {
    new AtomicLongHandle(ptr)
  }
}

private[choam] final class AtomicLongHandle private (private val ptr: Ptr[atomic_llong]) extends AnyVal {

  final def getAcquire: Long = {
    atomic_load_explicit(ptr, memory_order_acquire)
  }

  final def getOpaque: Long = {
    atomic_load_explicit(ptr, memory_order_relaxed)
  }

  final def setRelease(nv: Long): Unit = {
    atomic_store_explicit(ptr, nv, memory_order_release)
  }

  final def setOpaque(nv: Long): Unit = {
    atomic_store_explicit(ptr, nv, memory_order_relaxed)
  }

  final def compareAndSet(ov: Long, nv: Long): Boolean = {
    val expected: Ptr[Long] = stackalloc[Long]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, memory_order_seq_cst, memory_order_acquire)
  }

  final def compareAndExchange(ov: Long, nv: Long): Long = {
    _compareAndExchange(ov, nv, memory_order_seq_cst, memory_order_acquire)
  }

  final def compareAndExchangeAcquire(ov: Long, nv: Long): Long = {
    _compareAndExchange(ov, nv, memory_order_acquire, memory_order_acquire)
  }

  final def compareAndExchangeRelAcq(ov: Long, nv: Long): Long = {
    _compareAndExchange(ov, nv, memory_order_acq_rel, memory_order_acquire)
  }

  private[this] final def _compareAndExchange(ov: Long, nv: Long, moSuccess: memory_order, moFailure: memory_order): Long = {
    val expected: Ptr[Long] = stackalloc[Long]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, moSuccess, moFailure) : Unit
    !expected
  }

  final def getAndAddOpaque(delta: Long): Long = {
    atomic_fetch_add_explicit(ptr, delta, memory_order_relaxed)
  }

  final def getAndAddAcquire(delta: Long): Long = {
    atomic_fetch_add_explicit(ptr, delta, memory_order_acquire)
  }
}
