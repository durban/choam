/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

import scala.math.Ordering

import cats.kernel.Order

/**
 * Extension point for MCAS: an MCAS operation
 * can be executed on any number of objects
 * which conform to this interface.
 *
 * However, the methods of this interface should
 * only ever be called by the MCAS implementation.
 * An MCAS operation is only safe and atomic if
 * ALL reads and writes go through the MCAS
 * implementation.
 *
 * These are the low-level, primitive operations
 * required by the MCAS implementation. They are
 * easily implemented by, e.g., having an
 * `AtomicReference` or similar. (For implementations
 * of this interface, see `SimpleMemoryLocation` or
 * the various `Ref`s in the `choam-core` module.)
 *
 * Some method names are prefixed by `unsafe` because
 * these are necessarily side-effecting methods,
 * and they're also very low-level. Other methods are
 * not "unsafe", since they're mostly harmless.
 *
 * Generally, this interface should not be used
 * directly. Instead, use MCAS, or an even higher
 * level abstraction.
 */
trait MemoryLocation[A] {

  // contents:

  def unsafeGetVolatile(): A

  def unsafeGetPlain(): A

  def unsafeSetVolatile(nv: A): Unit

  def unsafeSetPlain(nv: A): Unit

  def unsafeCasVolatile(ov: A, nv: A): Boolean

  def unsafeCmpxchgVolatile(ov: A, nv: A): A

  // version:

  def unsafeGetVersionVolatile(): Long

  def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long

  // marker:

  /** Used by EMCAS */ // TODO: this is JVM-only
  def unsafeGetMarkerVolatile(): WeakReference[AnyRef]

  /** Used by EMCAS */ // TODO: this is JVM-only
  def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean

  // ID:

  def id0: Long

  def id1: Long

  def id2: Long

  def id3: Long

  // private utilities:

  private[mcas] final def cast[B]: MemoryLocation[B] =
    this.asInstanceOf[MemoryLocation[B]]
}

object MemoryLocation extends MemoryLocationInstances0 {

  def unsafe[A](initial: A): MemoryLocation[A] =
    unsafeUnpadded[A](initial)

  def unsafeUnpadded[A](initial: A): MemoryLocation[A] = {
    val tlr = ThreadLocalRandom.current()
    unsafeUnpaddedWithId(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  def unsafePadded[A](initial: A): MemoryLocation[A] = {
    val tlr = ThreadLocalRandom.current()
    unsafePaddedWithId(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  private[mcas] def unsafeWithId[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): MemoryLocation[A] =
    unsafeUnpaddedWithId(initial)(i0, i1, i2, i3)

  private[this] def unsafeUnpaddedWithId[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): MemoryLocation[A] = {
    new SimpleMemoryLocation[A](initial)(i0, i1, i2, i3)
  }

  private[this] def unsafePaddedWithId[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): MemoryLocation[A] = {
    new PaddedMemoryLocation[A](initial, i0, i1, i2, i3)
  }

  final def globalCompare(a: MemoryLocation[_], b: MemoryLocation[_]): Int = {
    this.orderingInstance.compare(a.cast[Any], b.cast[Any])
  }
}

private[mcas] sealed abstract class MemoryLocationInstances0 extends MemoryLocationInstances1 { self: MemoryLocation.type =>

  private[mcas] val memoryLocationOrdering =
    new MemoryLocationOrdering[Any]

  implicit def orderingInstance[A]: Ordering[MemoryLocation[A]] =
    memoryLocationOrdering.asInstanceOf[Ordering[MemoryLocation[A]]]
}

private[mcas] sealed abstract class MemoryLocationInstances1 { self: MemoryLocation.type =>

  private[this] val _orderInstance = new Order[MemoryLocation[Any]] {
    final override def compare(x: MemoryLocation[Any], y: MemoryLocation[Any]): Int =
      self.globalCompare(x, y)
  }

  implicit def orderInstance[A]: Order[MemoryLocation[A]] =
    _orderInstance.asInstanceOf[Order[MemoryLocation[A]]]
}
