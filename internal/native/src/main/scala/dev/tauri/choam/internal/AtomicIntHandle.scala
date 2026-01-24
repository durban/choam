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

import scala.scalanative.annotation.alwaysinline
import scala.scalanative.unsafe.{ Ptr, stackalloc }
import scala.scalanative.libc.stdatomic.{
  atomic_int,
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

private[choam] object AtomicIntHandle extends AtomicIntHandleCompanionScalaVer {

  @alwaysinline
  final def newAtomicIntHandleDoNotCallThisMethod(ptr: Ptr[atomic_int]): AtomicIntHandle = {
    new AtomicIntHandle(ptr)
  }
}

private[choam] final class AtomicIntHandle private (private val ptr: Ptr[atomic_int]) extends AnyVal {

  final def getVolatile: Int = {
    atomic_load_explicit(ptr, memory_order_seq_cst)
  }

  final def getAcquire: Int = {
    atomic_load_explicit(ptr, memory_order_acquire)
  }

  final def getOpaque: Int = {
    atomic_load_explicit(ptr, memory_order_relaxed)
  }

  final def setRelease(nv: Int): Unit = {
    atomic_store_explicit(ptr, nv, memory_order_release)
  }

  final def setOpaque(nv: Int): Unit = {
    atomic_store_explicit(ptr, nv, memory_order_relaxed)
  }

  final def compareAndSet(ov: Int, nv: Int): Boolean = {
    val expected: Ptr[Int] = stackalloc[Int]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, memory_order_seq_cst, memory_order_acquire)
  }

  final def compareAndExchange(ov: Int, nv: Int): Int = {
    _compareAndExchange(ov, nv, memory_order_seq_cst, memory_order_acquire)
  }

  final def compareAndExchangeAcquire(ov: Int, nv: Int): Int = {
    _compareAndExchange(ov, nv, memory_order_acquire, memory_order_acquire)
  }

  final def compareAndExchangeRelAcq(ov: Int, nv: Int): Int = {
    _compareAndExchange(ov, nv, memory_order_acq_rel, memory_order_acquire)
  }

  private[this] final def _compareAndExchange(ov: Int, nv: Int, moSuccess: memory_order, moFailure: memory_order): Int = {
    val expected: Ptr[Int] = stackalloc[Int]()
    !expected = ov
    atomic_compare_exchange_strong_explicit(ptr, expected, nv, moSuccess, moFailure) : Unit
    !expected
  }

  final def getAndAddOpaque(delta: Int): Int = {
    atomic_fetch_add_explicit(ptr, delta, memory_order_relaxed)
  }

  final def getAndAddAcquire(delta: Int): Int = {
    atomic_fetch_add_explicit(ptr, delta, memory_order_acquire)
  }
}
