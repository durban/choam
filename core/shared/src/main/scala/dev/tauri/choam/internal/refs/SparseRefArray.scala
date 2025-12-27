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

import cats.data.Chain

import core.{ Ref, Rxn, RxnImpl }

private sealed abstract class SparseXRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends SparseRefArrayBase[A](__size, box(initial), _idBase) // TODO: try to avoid `box`
  with Ref.UnsealedArray[A] {

  protected[this] type RefT[a] <: RefArrayRef[a]

  protected[this] def createRef(i: Int): RefT[A]

  require((__size > 0) && (((__size - 1) * 3 + 2) > (__size - 1))) // avoid overflow

  final override def length: Int =
    this._size

  protected[this] final def getOrNull(idx: Int): RefT[A] = {
    if ((idx >= 0) && (idx < length)) {
      this.getOrCreateRef(idx)
    } else {
      nullOf[RefT[A]]
    }
  }

  protected[this] final def getOrCreateRef(i: Int): RefT[A] = {
    val refIdx = 3 * i
    val existing = this.getO(refIdx)
    if (existing ne null) {
      // `RefArrayRef` has only final fields,
      // so it's "safely initialized", so if
      // we've read something here with `getO`,
      // then we also can see its fields:
      existing.asInstanceOf[RefT[A]]
    } else {
      val nv = this.createRef(i)
      // opaque is enough, see above:
      this.cmpxchgO(refIdx, null, nv) match {
        case null => nv // we're the first
        case other => other.asInstanceOf[RefT[A]]
      }
    }
  }

  final override def unsafeGet(idx: Int): RxnImpl[A] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).get
  }

  final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).set(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): RxnImpl[Unit] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).update(f)
  }

  final override def unsafeModify[B](idx: Int, f: A => (A, B)): RxnImpl[B] = {
    this.checkIndex(idx)
    this.getOrCreateRef(idx).modify(f)
  }

  final override def get(idx: Int): RxnImpl[Option[A]] = {
    this.getOrNull(idx) match {
      case null => Rxn.noneImpl
      case ref => ref.get.map(Some(_))
    }
  }

  final override def set(idx: Int, nv: A): RxnImpl[Boolean] = {
    this.getOrNull(idx) match {
      case null => Rxn.falseImpl
      case ref => ref.set(nv).as(true)
    }
  }

  final override def update(idx: Int, f: A => A): RxnImpl[Boolean] = {
    this.getOrNull(idx) match {
      case null => Rxn.falseImpl
      case ref => ref.update(f).as(true)
    }
  }

  final override def refs: Chain[Ref[A]] = {
    val arr = Array.tabulate[Ref[A]](length) { idx =>
      this.getOrCreateRef(idx)
    }
    Chain.fromSeq(scala.collection.immutable.ArraySeq.unsafeWrapArray(arr))
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

  protected[this] final override def createRef(i: Int): RefArrayTRef[A] = {
    new RefArrayTRef[A](this, i)
  }
}

