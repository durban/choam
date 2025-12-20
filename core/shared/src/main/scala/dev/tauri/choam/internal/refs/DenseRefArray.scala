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

import core.Ref

private sealed class DenseRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends DenseRefArrayBase[A](__size, box(initial), _idBase) // TODO: try to avoid `box`
  with Ref.UnsealedArray[A] {

  require((__size > 0) && (((__size - 1) * 3 + 2) > (__size - 1))) // avoid overflow

  final override def size: Int =
    this._size

  final override def apply(idx: Int): Option[Ref[A]] =
    Option(this.getOrNull(idx))

  final override def unsafeApply(idx: Int): Ref[A] = {
    this.checkIndex(idx)
    this.getOrNull(idx)
  }

  override def createRef(i: Int): RefArrayRef[A] = {
    new RefArrayRef[A](this, i)
  }

  private[this] final def getOrNull(idx: Int): Ref[A] = {
    if ((idx >= 0) && (idx < size)) {
      val refIdx = 3 * idx
      // `RefArrayRef`s were initialized into
      // a final field (`items`), and they
      // never change, so we can read with plain:
      this.getP(refIdx).asInstanceOf[Ref[A]]
    } else {
      null
    }
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

  private[this] final def getOrNull(idx: Int): stm.TRef[A] = {
    if ((idx >= 0) && (idx < size)) {
      val refIdx = 3 * idx
      // `RefArrayTRef`s were initialized into
      // a final field (`items`), and they
      // never change, so we can read with plain:
      this.getP(refIdx).asInstanceOf[stm.TRef[A]]
    } else {
      null
    }
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
