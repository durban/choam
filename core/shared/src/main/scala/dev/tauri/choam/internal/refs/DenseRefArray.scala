/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import core.{ Ref, Rxn, RxnImpl }

private sealed class DenseRefArray[A](
  __size: Int,
  initial: A,
  _idBase: Long,
) extends DenseRefArrayBase[A](__size, box(initial), _idBase) // TODO: try to avoid `box`
  with Ref.UnsealedArray[A] { self =>

  require((__size > 0) && (((__size - 1) * 3 + 2) > (__size - 1))) // avoid overflow

  final override def length: Int =
    this._size

  override def createRef(i: Int): RefArrayRef[A] = {
    new RefArrayRef[A](this, i)
  }

  private[choam] final override def getOrCreateRefOrNull(idx: Int): Ref[A] = {
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
    this.getOrCreateRefOrNull(idx).getImpl
  }

  final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
    this.checkIndex(idx)
    this.getOrCreateRefOrNull(idx).setImpl(nv)
  }

  final override def unsafeUpdate(idx: Int)(f: A => A): RxnImpl[Unit] = {
    this.checkIndex(idx)
    this.getOrCreateRefOrNull(idx).updateImpl(f)
  }

  final override def unsafeModify[B](idx: Int)(f: A => (A, B)): RxnImpl[B] = {
    this.checkIndex(idx)
    this.getOrCreateRefOrNull(idx).modifyImpl(f)
  }

  private[choam] final override def unsafeFlatModify[B](idx: Int)(f: A => (A, Rxn[B])): RxnImpl[B] = {
    this.checkIndex(idx)
    this.getOrCreateRefOrNull(idx).flatModifyImpl(f)
  }

  final override def get(idx: Int): RxnImpl[Option[A]] = {
    this.getOrCreateRefOrNull(idx) match {
      case null => Rxn.noneImpl
      case ref => ref.getImpl.map(Some(_))
    }
  }

  final override def set(idx: Int, nv: A): RxnImpl[Boolean] = {
    this.getOrCreateRefOrNull(idx) match {
      case null => Rxn.falseImpl
      case ref => ref.setImpl(nv).as(true)
    }
  }

  final override def update(idx: Int)(f: A => A): RxnImpl[Boolean] = {
    this.getOrCreateRefOrNull(idx) match {
      case null => Rxn.falseImpl
      case ref => ref.updateImpl(f).as(true)
    }
  }

  final override def modify[B](idx: Int)(f: A => (A, B)): RxnImpl[Option[B]] = {
    this.getOrCreateRefOrNull(idx) match {
      case null => Rxn.noneImpl
      case ref => ref.modifyImpl(f).map(Some(_))
    }
  }

  final override def refs: IndexedSeq[Ref[A]] = {
    new IndexedSeq[Ref[A]] {
      final override def apply(idx: Int): Ref[A] = {
        self.checkIndex(idx)
        self.getOrCreateRefOrNull(idx)
      }
      final override def length: Int = {
        self.length
      }
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
}
