/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package refs

import java.util.concurrent.atomic.AtomicReferenceArray

import mcas.MemoryLocation

private final class RefArray[A](
  val size: Int,
  i0: Long,
  i1: Long,
  i2: Long,
  i3: Int, // LSB is array index
) extends RefIdOnly(i0, i1, i2, i3.toLong << 32)
  with Ref.Array[A] {

  import RefArray.RefArrayRef

  require(size >= 0)

  private val items: AtomicReferenceArray[AnyRef] = {
    // TODO: padding
    new AtomicReferenceArray[AnyRef](size)
  }

  private val refs: Array[RefArrayRef[A]] = {
    val a = new Array[RefArrayRef[A]](size)
    var i = 0
    while (i < size) {
      a(i) = new RefArrayRef[A](this, i)
      i += 1
    }
    a
  }

  private[choam] def unsafeSetAll(to: A): Unit = {
    val value = to.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      items.set(i, value)
      i += 1
    }
  }

  def apply(idx: Int): Ref[A] = {
    require((idx >= 0) && (idx < size))
    refs(idx)
  }

  protected[refs] final override def refToString(): String = {
    val h = (id0 ^ id1 ^ id2 ^ id3) & (~0xffff)
    s"RefArray[${size}]@${java.lang.Long.toHexString(h >> 16)}"
  }
}

object RefArray {

  private final class RefArrayRef[A](
    array: RefArray[A],
    idx: Int
  ) extends Ref[A] with MemoryLocation[A] {

    final override def unsafeGetVolatile(): A =
      array.items.get(idx).asInstanceOf[A]

    final override def unsafeSetVolatile(nv: A): Unit =
      array.items.set(idx, nv.asInstanceOf[AnyRef])

    final override def unsafeCasVolatile(ov: A, nv: A): Boolean =
      array.items.compareAndSet(idx, ov.asInstanceOf[AnyRef], nv.asInstanceOf[AnyRef])

    final override def unsafeCmpxchgVolatile(ov: A, nv: A): A =
      array.items.compareAndExchange(idx, ov.asInstanceOf[AnyRef], nv.asInstanceOf[AnyRef]).asInstanceOf[A]

    final override def id0: Long =
      array.id0

    final override def id1: Long =
      array.id1

    final override def id2: Long =
      array.id2

    final override def id3: Long =
      array.id3 | idx.toLong

    final override def toString: String =
      refs.refStringFromIdsAndIdx(id0, id1, id2, id3, idx)

    private[choam] final override def dummy(v: Long): Long =
      v ^ id2
  }
}
