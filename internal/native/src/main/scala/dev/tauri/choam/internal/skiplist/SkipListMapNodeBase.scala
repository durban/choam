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
package skiplist

import scala.scalanative.runtime.{ Intrinsics, fromRawPtr }
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.libc.stdatomic.{
  AtomicRef,
  PtrToAtomicRef,
}
import scala.scalanative.libc.stdatomic.memory_order.{ memory_order_acquire, memory_order_seq_cst }

private[skiplist] abstract class SkipListMapNodeBase[V, N <: SkipListMapNodeBase[V, N]] protected[this] (
  v: V,
  n: N
) {

  // plain writes are fine here, since
  // new `Node`s are alway published
  // with a volatile-CAS:
  @unused private[this] var value: AnyRef = box(v)
  @unused private[this] var next: N = n

  protected[this] final def yesWeNeedTheseFieldsEvenOnDotty(): Unit = {
    this.value : Unit
    this.next : Unit
  }

  @alwaysinline
  private[this] final def atomicValue: AtomicRef[AnyRef] = {
    fromRawPtr[AnyRef](Intrinsics.classFieldRawPtr(this, "value")).atomic
  }

  @alwaysinline
  private[this] final def atomicNext: AtomicRef[N] = {
    fromRawPtr[N](Intrinsics.classFieldRawPtr(this, "next")).atomic
  }

  final def getValue(): V = {
    atomicValue.load(memory_order_acquire).asInstanceOf[V]
  }

  final def casValue(ov: V, nv: V): Boolean = {
    atomicValue.compareExchangeStrong(box(ov), box(nv), memory_order_seq_cst, memory_order_acquire)
  }

  final def getNext(): N = {
    atomicNext.load(memory_order_acquire)
  }

  final def casNext(ov: N, nv: N): Boolean = {
    atomicNext.compareExchangeStrong(ov, nv, memory_order_seq_cst, memory_order_acquire)
  }
}
