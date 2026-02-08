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

import scala.reflect.ClassTag

import core.{ Ref, Rxn, RxnImpl }
import mcas.RefIdGen

private[choam] sealed abstract class DenseArrayOfXRefs[A](
  final override val length: Int,
  initial: A,
  str: AllocationStrategy,
  rig: RefIdGen,
) extends Ref.UnsealedArray0[A] { self =>

  protected[this] type RefT[a] <: Ref[a]

  require(length > 0)

  protected[this] def createRef(initial: A, str: AllocationStrategy, rig: RefIdGen): RefT[A]

  protected[this] implicit def refTTag: ClassTag[RefT[A]]

  protected[this] val arr: scala.Array[RefT[A]] = {
    val a = new scala.Array[RefT[A]](length)
    var idx = 0
    while (idx < length) {
      a(idx) = this.createRef(initial, str, rig)
      idx += 1
    }
    a.asInstanceOf[scala.Array[RefT[A]]]
  }

  final override def unsafeGet(idx: Int): RxnImpl[A] = {
    jsCheckIdx(idx, length)
    this.arr(idx).getImpl
  }

  final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
    jsCheckIdx(idx, length)
    this.arr(idx).setImpl(nv)
  }

  final override def unsafeUpdate(idx: Int)(f: A => A): RxnImpl[Unit] = {
    jsCheckIdx(idx, length)
    this.arr(idx).updateImpl(f)
  }

  final override def unsafeModify[B](idx: Int)(f: A => (A, B)): RxnImpl[B] = {
    jsCheckIdx(idx, length)
    this.arr(idx).modifyImpl(f)
  }

  private[choam] final override def unsafeFlatModify[B](idx: Int)(f: A => (A, Rxn[B])): RxnImpl[B] = {
    jsCheckIdx(idx, length)
    this.arr(idx).flatModifyImpl(f)
  }

  private[choam] final override def getOrCreateRefOrNull(idx: Int): RefT[A] = {
    if ((idx >= 0) && (idx < length)) {
      this.arr(idx)
    } else {
      nullOf[RefT[A]]
    }
  }

  final override def get(idx: Int): RxnImpl[Option[A]] = {
    this.getOrCreateRefOrNull(idx) match {
      case null => Rxn.noneImpl[A]
      case tref => tref.getImpl.map(Some(_))
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
        jsCheckIdx(idx, length)
        self.arr(idx)
      }
      final override def length: Int = {
        self.length
      }
    }
  }
}

private[choam] final class DenseArrayOfRefs[A](
  size: Int,
  initial: A,
  str: AllocationStrategy,
  rig: RefIdGen,
) extends DenseArrayOfXRefs[A](size, initial, str, rig) {

  protected[this] final override type RefT[a] = Ref[a]

  protected[this] def createRef(initial: A, str: AllocationStrategy, rig: RefIdGen): RefT[A] =
    Ref.unsafe(initial, str, rig)

  protected[this] def refTTag: ClassTag[RefT[A]] =
    ClassTag[Ref[A]](classOf[Ref[A]])
}

private[choam] final class DenseArrayOfTRefs[A](
  size: Int,
  initial: A,
  str: AllocationStrategy,
  rig: RefIdGen,
) extends DenseArrayOfXRefs[A](size, initial, str, rig)
  with stm.TArray.UnsealedTArray[A] {

  protected[this] final override type RefT[a] = Ref[a] with stm.TRef[a]

  protected[this] final override def createRef(initial: A, str: AllocationStrategy, rig: RefIdGen): RefT[A] =
    Ref.unsafeTRef(initial, str, rig)

  protected[this] def refTTag: ClassTag[RefT[A]] =
    ClassTag[RefT[A]](classOf[Ref[A]])
}