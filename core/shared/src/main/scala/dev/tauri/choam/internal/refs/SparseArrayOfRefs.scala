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

import scala.reflect.ClassTag

import cats.data.Chain

import core.{ Ref, Rxn, RxnImpl }
import mcas.RefIdGen
import CompatPlatform.AtomicReferenceArray

sealed abstract class SparseArrayOfXRefs[A](
  final override val length: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends Ref.UnsealedArray0[A] {

  protected[this] type RefT[a] <: Ref[a]

  require(length > 0)

  protected[this] def createRef(initial: A, str: Ref.AllocationStrategy, id: Long): RefT[A]

  protected[this] implicit def refTTag: ClassTag[RefT[A]]

  private[this] val arr: AtomicReferenceArray[RefT[A]] =
    new AtomicReferenceArray[RefT[A]](length)

  private[this] val idBase: Long =
    rig.nextArrayIdBase(size = length)

  protected[this] final def unsafeApplyInternal(idx: Int): RefT[A] = {
    val arr = this.arr
    arr.getOpaque(idx) match { // FIXME: reading a `Ref` with a race!
      case null =>
        val nv = this.createRef(initial, str, RefIdGen.compute(this.idBase, idx))
        val wit = arr.compareAndExchange(idx, nullOf[RefT[A]], nv)
        if (wit eq null) {
          nv // we're the first
        } else {
          wit // found other
        }
      case ref =>
        ref
    }
  }

  final override def unsafeGet(idx: Int): RxnImpl[A] = {
    unsafeApplyInternal(idx).getImpl
  }

  final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
    unsafeApplyInternal(idx).setImpl(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): RxnImpl[Unit] = {
    unsafeApplyInternal(idx).updateImpl(f)
  }

  final override def unsafeModify[B](idx: Int, f: A => (A, B)): RxnImpl[B] = {
    unsafeApplyInternal(idx).modifyImpl(f)
  }

  final override def get(idx: Int): RxnImpl[Option[A]] = {
    if ((idx >= 0) && (idx < length)) {
      unsafeGet(idx).map(Some(_))
    } else {
      Rxn.noneImpl
    }
  }

  final override def refs: Chain[Ref[A]] = {
    val arr = Array.tabulate(length) { idx =>
      this.unsafeApplyInternal(idx)
    }
    Chain.fromSeq(scala.collection.immutable.ArraySeq.unsafeWrapArray(arr))
  }
}

private[choam] final class SparseArrayOfRefs[A](
  size: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends SparseArrayOfXRefs[A](size, initial, str, rig) {

  protected[this] final override type RefT[a] = Ref[a]

  protected[this] final override def createRef(initial: A, str: Ref.AllocationStrategy, id: Long): RefT[A] =
    Ref.unsafeWithId(initial, str, id)

  protected[this] final override def refTTag: ClassTag[RefT[A]] =
    ClassTag[Ref[A]](classOf[Ref[_]])
}

private[choam] final class SparseArrayOfTRefs[A](
  size: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends SparseArrayOfXRefs[A](size, initial, str, rig)
  with stm.TArray.UnsealedTArray[A] {

  protected[this] final override type RefT[a] = Ref[a] with stm.TRef[a]

  protected[this] def createRef(initial: A, str: Ref.AllocationStrategy, id: Long): RefT[A] =
    stm.TRef.unsafeRefWithId(initial, id) // TODO: padded

  protected[this] final override def refTTag: ClassTag[RefT[A]] =
    ClassTag[Ref[A] with stm.TRef[A]](classOf[Ref[_]])

  final override def set(idx: Int, nv: A): stm.Txn[Boolean] = {
    if ((idx >= 0) && (idx < size)) {
      unsafeSet(idx, nv).as(true)
    } else {
      stm.Txn._false
    }
  }

  final override def update(idx: Int, f: A => A): stm.Txn[Boolean] = {
    if ((idx >= 0) && (idx < size)) {
      unsafeUpdate(idx, f).as(true)
    } else {
      stm.Txn._false
    }
  }
}
