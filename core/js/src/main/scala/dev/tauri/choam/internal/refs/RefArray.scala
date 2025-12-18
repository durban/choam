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

import java.lang.ref.WeakReference

import scala.collection.mutable.{ LongMap => MutLongMap }
import scala.runtime.LongRef

import core.{ Ref, UnsealedRef }
import internal.mcas.{ Mcas, MemoryLocation, Version, RefIdGen, Consts }
import RefArray.{ RefArrayRef, RefArrayTRef }
import stm.Txn

// TODO: we shouldn't have to duplicate all these for JS(?)

private abstract class RefArray[A](
  val size: Int,
  _idBase: Long,
) extends RefIdOnlyN(_idBase)
  with Ref.UnsealedArray[A] {

  protected def items: Array[AnyRef]

  final def idBase: Long =
    this.id
}

private sealed class DenseRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends RefArray[A](__size, _idBase) {

  protected[this] def createRef(i: Int): RefArrayRef[A] =
    new RefArrayRef[A](this, i)

  final override val items: Array[AnyRef] = {
    val ara = new Array[AnyRef](3 * size)
    val value = initial.asInstanceOf[AnyRef]
    var i = 0
    while (i < size) {
      val refIdx = 3 * i
      val itemIdx = refIdx + 1
      val versionIdx = refIdx + 2
      ara(refIdx) = this.createRef(itemIdx)
      ara(itemIdx) = value
      ara(versionIdx) = Version.BoxedStart
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeApply(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrNull(idx)
  }

  protected[this] def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < size)) {
      val refIdx = 3 * idx
      CompatPlatform.checkArrayIndexIfScalaJs(refIdx, this.items.length)
      this.items(refIdx).asInstanceOf[Ref[A]]
    } else {
      null
    }
  }
}

private sealed class DenseTRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends DenseRefArray[A](__size, initial, _idBase)
  with stm.TArray.UnsealedTArray[A] {

  final override def createRef(i: Int): RefArrayTRef[A] = {
    new RefArrayTRef[A](this, i)
  }

  protected[this] final override def getOrNull(idx: Int): RefArrayTRef[A] = {
    super.getOrNull(idx).asInstanceOf[RefArrayTRef[A]]
  }

  final override def get(idx: Int): stm.Txn[Option[A]] = {
    this.getOrNull(idx) match {
      case null => stm.Txn.none
      case tref => tref.get.map(Some(_))
    }
  }

  final override def set(idx: Int, nv: A): stm.Txn[Boolean] = {
    this.getOrNull(idx) match {
      case null => stm.Txn._false
      case tref => tref.set(nv).as(true)
    }
  }

  final override def update(idx: Int, f: A => A): stm.Txn[Boolean] = {
    this.getOrNull(idx) match {
      case null => stm.Txn._false
      case tref => tref.update(f).as(true)
    }
  }

  final override def unsafeGet(idx: Int): stm.Txn[A] = {
    this.checkIndex(idx)
    this.getOrNull(idx).get
  }

  final override def unsafeSet(idx: Int, nv: A): stm.Txn[Unit] = {
    this.checkIndex(idx)
    this.getOrNull(idx).set(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): stm.Txn[Unit] = {
    this.checkIndex(idx)
    this.getOrNull(idx).update(f)
  }
}

private sealed abstract class SparseXRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends RefArray[A](__size, _idBase) {

  protected[this] type RefT[a] <: RefArrayRef[a]

  protected[this] def createRef(i: Int): RefT[A]

  private[this] val initVal: AnyRef =
    box(initial)

  final override val items: Array[AnyRef] = {
    val ara = new Array[AnyRef](3 * size)
    var i = 0
    while (i < size) {
      val itemIdx = (3 * i) + 1
      ara(itemIdx) = initVal
      val versionIdx = itemIdx + 1
      ara(versionIdx) = Version.BoxedStart
      i += 1
    }
    ara
  }

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeApply(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx)
  }

  protected[this] final def getOrNull(idx: Int): RefT[A] = {
    if ((idx >= 0) && (idx < size)) {
      this.getOrCreateRef(idx)
    } else {
      null.asInstanceOf[RefT[A]]
    }
  }

  protected[this] final def getOrCreateRef(i: Int): RefT[A] = {
    val items = this.items
    val refIdx = 3 * i
    CompatPlatform.checkArrayIndexIfScalaJs(refIdx, items.length)
    val existing = items(refIdx)
    if (existing ne null) {
      existing.asInstanceOf[RefT[A]]
    } else {
      val itemIdx = refIdx + 1
      val nv = createRef(itemIdx)
      items(refIdx) = nv
      nv
    }
  }
}

private final class SparseRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends SparseXRefArray[A](__size, initial, _idBase) {

  protected[this] final override type RefT[a] = RefArrayRef[a]

  protected[this] def createRef(i: Int): RefT[A] = {
    new RefArrayRef[A](this, i)
  }
}

private final class SparseTRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends SparseXRefArray[A](__size, initial, _idBase)
  with stm.TArray.UnsealedTArray[A] {

  protected[this] final override type RefT[a] = RefArrayTRef[a]

  protected[this] def createRef(i: Int): RefT[A] = {
    new RefArrayTRef[A](this, i)
  }

  final override def unsafeGet(idx: Int): Txn[A] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).get
  }

  final override def unsafeSet(idx: Int, nv: A): Txn[Unit] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).set(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): Txn[Unit] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).update(f)
  }

  final override def get(idx: Int): Txn[Option[A]] = {
    this.getOrNull(idx) match {
      case null => Txn.pure(None)
      case tref => tref.get.map(Some(_))
    }
  }

  final override def set(idx: Int, nv: A): Txn[Boolean] = {
    this.getOrNull(idx) match {
      case null => Txn._false
      case tref => tref.set(nv).as(true)
    }
  }

  final override def update(idx: Int, f: A => A): Txn[Boolean] = {
    this.getOrNull(idx) match {
      case null => Txn._false
      case tref => tref.update(f).as(true)
    }
  }
}

private object RefArray {

  private[refs] final class RefArrayTRef[A](
    array: RefArray[A],
    physicalIdx: Int,
  ) extends RefArrayRef[A](array, physicalIdx)
    with stm.TRef.UnsealedTRef[A]
    with stm.SinglethreadedTRefImpl[A] {

    private[this] val listeners: MutLongMap[Null => Unit] =
      MutLongMap.empty[Null => Unit]

    private[this] val previousListenerId: LongRef =
      LongRef.create(Consts.InvalidListenerId)

    // Note that we must override these, as the defaults we inherit are incorrect for TRef:

    private[choam] final override def withListeners: MemoryLocation.WithListeners =
      this

    private[choam] final override def unsafeNotifyListeners(): Unit =
      this.unsafeNotifyListenersImpl(this.listeners)

    private[choam] final override def unsafeRegisterListener(
      ctx: Mcas.ThreadContext,
      listener: Null => Unit,
      lastSeenVersion: Long,
    ): Long = this.unsafeRegisterListenerImpl(this.listeners, this.previousListenerId, ctx, listener, lastSeenVersion)

    private[choam] final override def unsafeCancelListener(lid: Long): Unit =
      this.unsafeCancelListenerImpl(this.listeners, lid)

    private[choam] final override def unsafeNumberOfListeners(): Int =
      this.unsafeNumberOfListenersImpl(this.listeners)

    final override def toString: String =
      refs.refArrayRefToString("ATRef", array.idBase, this.logicalIdx)
  }

  private[refs] sealed class RefArrayRef[A](
    array: RefArray[A],
    physicalIdx: Int,
  ) extends core.RefGetAxn[A] with UnsealedRef[A] with MemoryLocation[A] {

    final override def hashCode: Int =
      this.id.toInt

    final override def unsafeGetV(): A =
      array.items(physicalIdx).asInstanceOf[A]

    final override def unsafeGetP(): A =
      array.items(physicalIdx).asInstanceOf[A]

    final override def unsafeSetV(nv: A): Unit =
      array.items(physicalIdx) = nv.asInstanceOf[AnyRef]

    final override def unsafeSetP(nv: A): Unit =
      array.items(physicalIdx) = nv.asInstanceOf[AnyRef]

    final override def unsafeCasV(ov: A, nv: A): Boolean = {
      val wit = array.items(physicalIdx)
      if (wit eq ov.asInstanceOf[AnyRef]) {
        array.items(physicalIdx) = nv.asInstanceOf[AnyRef]
        true
      } else {
        false
      }
    }

    final override def unsafeCmpxchgV(ov: A, nv: A): A = {
      val wit = array.items(physicalIdx)
      if (wit eq ov.asInstanceOf[AnyRef]) {
        array.items(physicalIdx) = nv.asInstanceOf[AnyRef]
      }
      wit.asInstanceOf[A]
    }

    final override def unsafeCmpxchgR(ov: A, nv: A): A = {
      this.unsafeCmpxchgV(ov, nv)
    }

    final override def unsafeGetVersionV(): Long =
      array.items(physicalIdx + 1).asInstanceOf[Long]

    final override def unsafeCmpxchgVersionV(ov: Long, nv: Long): Long = {
      val idx = physicalIdx + 1
      val currentValue: Long = array.items(idx).asInstanceOf[Long]
      if (currentValue == ov) {
        array.items(idx) = nv.asInstanceOf[AnyRef]
      }
      currentValue
    }

    final override def unsafeGetMarkerV(): WeakReference[AnyRef] =
      impossible("RefArrayRef#unsafeGetMarkerV called on JS")

    final override def unsafeCasMarkerV(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
      impossible("RefArrayRef#unsafeCasMarkerV called on JS")

    final override def unsafeCmpxchgMarkerR(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef] =
      impossible("RefArrayRef#unsafeCmpxchgMarkerR called on JS")

    final override def id: Long = {
      RefIdGen.compute(array.idBase, this.logicalIdx)
    }

    override def toString: String = {
      refs.refArrayRefToString("ARef", array.idBase, this.logicalIdx)
    }

    protected[this] final def logicalIdx: Int =
      this.physicalIdx / 3 // truncated division is OK here

    private[choam] final override def dummy(v: Byte): Long =
      v ^ id
  }
}
