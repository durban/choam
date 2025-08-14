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
  atomic_load_explicit,
  atomic_store_explicit,
  atomic_compare_exchange_strong_explicit,
}
import scala.scalanative.libc.stdatomic.memory_order.{
  memory_order_relaxed,
  memory_order_acquire,
  memory_order_release,
  memory_order_seq_cst,
}

private[choam] object AtomicHandle {

  import scala.language.experimental.macros

  final def apply[O <: AnyRef, H](obj: O, fieldName: String): AtomicHandle[H] =
    macro AtomicHandleMacros.applyImpl[O, H]

  @alwaysinline
  final def newAtomicHandleDoNotCallThisMethod[A](ptr: Ptr[AnyRef]): AtomicHandle[A] = {
    new AtomicHandle[A](ptr)
  }
}

private[choam] final class AtomicHandle[A] private (private val ptr: Ptr[AnyRef]) extends AnyVal {

  final def getAcquire: A = {
    atomic_load_explicit(ptr, memory_order_acquire).asInstanceOf[A]
  }

  final def getOpaque: A = {
    atomic_load_explicit(ptr, memory_order_relaxed).asInstanceOf[A]
  }

  final def setRelease(nv: A): Unit = {
    atomic_store_explicit(ptr, nv.asInstanceOf[AnyRef], memory_order_release)
  }

  final def setOpaque(nv: A): Unit = {
    atomic_store_explicit(ptr, nv.asInstanceOf[AnyRef], memory_order_relaxed)
  }

  final def compareAndSet(ov: A, nv: A): Boolean = {
    val expected: Ptr[AnyRef] = stackalloc[AnyRef]()
    !expected = ov.asInstanceOf[AnyRef]
    atomic_compare_exchange_strong_explicit(
      ptr,
      expected,
      nv.asInstanceOf[AnyRef],
      memory_order_seq_cst,
      memory_order_acquire,
    )
  }

  final def compareAndExchange(ov: A, nv: A): A = {
    val expected: Ptr[AnyRef] = stackalloc[AnyRef]()
    !expected = ov.asInstanceOf[AnyRef]
    atomic_compare_exchange_strong_explicit(ptr, expected, nv.asInstanceOf[AnyRef], memory_order_seq_cst, memory_order_acquire) : Unit
    (!expected).asInstanceOf[A]
  }
}
