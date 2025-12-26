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

import core.{ Ref, RxnImpl }

private sealed class DenseRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends DenseRefArrayBase[A](__size, box(initial), _idBase) // TODO: try to avoid `box`
  with Ref.UnsealedArray[A] {

  require((__size > 0) && (((__size - 1) * 3 + 2) > (__size - 1))) // avoid overflow

  final override def length: Int =
    this._size

  override def createRef(i: Int): RefArrayRef[A] = {
    new RefArrayRef[A](this, i)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < length)) {
      val refIdx = 3 * idx
      // `RefArrayRef`s were initialized into
      // a final field (`items`), and they
      // never change, so we can read with plain:
      this.getP(refIdx).asInstanceOf[Ref[A]]
    } else {
      null
    }
  }

  final override def unsafeGet(idx: Int): RxnImpl[A] = {
    this.checkIndex(idx)
    this.getOrNull(idx).getImpl
  }

  final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
    this.checkIndex(idx)
    this.getOrNull(idx).setImpl(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): RxnImpl[Unit] = {
    this.checkIndex(idx)
    this.getOrNull(idx).updateImpl(f)
  }

  final override def refs: Chain[Ref[A]] = {
    val arr = Array.tabulate(length) { idx =>
      this.getOrNull(idx)
    }
    Chain.fromSeq(scala.collection.immutable.ArraySeq.unsafeWrapArray(arr))
  }
}

private final class DenseTRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends DenseRefArray[A](__size, initial, _idBase) with stm.TArray.UnsealedTArray[A] {

  final override def createRef(i: Int): RefArrayTRef[A] = {
    new RefArrayTRef[A](this, i)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] with stm.TRef[A] = {
    if ((idx >= 0) && (idx < length)) {
      val refIdx = 3 * idx
      // `RefArrayTRef`s were initialized into
      // a final field (`items`), and they
      // never change, so we can read with plain:
      this.getP(refIdx).asInstanceOf[Ref[A] with stm.TRef[A]] // TODO: avoid cast
    } else {
      null
    }
  }

  final override def get(idx: Int): stm.Txn[Option[A]] = {
    (this.getOrNull(idx) : stm.TRef[A]) match {
      case null => stm.Txn.none
      case tref => tref.get.map(Some(_))
    }
  }

  final override def set(idx: Int, nv: A): stm.Txn[Boolean] = {
    (this.getOrNull(idx) : stm.TRef[A]) match {
      case null => stm.Txn._false
      case tref => tref.set(nv).as(true)
    }
  }

  final override def update(idx: Int, f: A => A): stm.Txn[Boolean] = {
    (this.getOrNull(idx) : stm.TRef[A]) match {
      case null => stm.Txn._false
      case tref => tref.update(f).as(true)
    }
  }
}
