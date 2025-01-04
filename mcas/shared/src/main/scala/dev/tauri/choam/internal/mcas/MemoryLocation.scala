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
package mcas

import java.lang.ref.WeakReference

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
trait MemoryLocation[A] extends Hamt.HasHash {

  // contents:

  /** volatile */
  def unsafeGetV(): A

  /** plain */
  def unsafeGetP(): A

  /** volatile */
  def unsafeSetV(nv: A): Unit

  /** plain */
  def unsafeSetP(nv: A): Unit

  /** volatile */
  def unsafeCasV(ov: A, nv: A): Boolean

  /** volatile */
  def unsafeCmpxchgV(ov: A, nv: A): A

  /** release */
  def unsafeCmpxchgR(ov: A, nv: A): A

  // version:

  /** volatile */
  def unsafeGetVersionV(): Long

  /** volatile */
  def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long

  // marker:

  /** Used by EMCAS; volatile */ // TODO: this is JVM-only
  def unsafeGetMarkerV(): WeakReference[AnyRef]

  /** Used by EMCAS; volatile */ // TODO: this is JVM-only
  def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean

  /** Used by EMCAS; release */ // TODO: this is JVM-only
  def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef]

  // ID:

  def id: Long

  final override def hash: Long =
    this.id

  // listeners (for STM):

  private[choam] def withListeners: MemoryLocation.WithListeners =
    throw new UnsupportedOperationException

  private[choam] def unsafeNotifyListeners(): Unit =
    ()

  // private utilities:

  private[mcas] final def cast[B]: MemoryLocation[B] =
    this.asInstanceOf[MemoryLocation[B]]
}

object MemoryLocation extends MemoryLocationInstances0 {

  private[choam] trait WithListeners {

    private[choam] def unsafeRegisterListener(
      ctx: Mcas.ThreadContext,
      listener: Null => Unit,
      lastSeenVersion: Long,
    ): Long

    private[choam] def unsafeCancelListener(lid: Long): Unit
  }

  def unsafe[A](initial: A): MemoryLocation[A] = // TODO: remove this
    unsafeUnpadded[A](initial)

  def unsafeUnpadded[A](initial: A): MemoryLocation[A] =
    this.unsafeUnpadded(initial, RefIdGen.global)

  def unsafeUnpadded[A](initial: A, rig: RefIdGen): MemoryLocation[A] = {
    unsafeUnpaddedWithId(initial)(rig.nextId())
  }

  def unsafePadded[A](initial: A): MemoryLocation[A] =
    this.unsafePadded(initial, RefIdGen.global)

  def unsafePadded[A](initial: A, rig: RefIdGen): MemoryLocation[A] = {
    unsafePaddedWithId(initial)(rig.nextId())
  }

  private[mcas] def unsafeWithId[A](initial: A)(i0: Long): MemoryLocation[A] =
    unsafeUnpaddedWithId(initial)(i0)

  private[mcas] def unsafeUnpaddedWithId[A](initial: A)(i0: Long): MemoryLocation[A] = {
    new SimpleMemoryLocation[A](initial)(i0)
  }

  private[this] def unsafePaddedWithId[A](initial: A)(id: Long): MemoryLocation[A] = {
    new PaddedMemoryLocation[A](initial, id)
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
