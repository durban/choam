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

import core.{ Ref, RxnImpl }
import mcas.RefIdGen

sealed abstract class DenseArrayOfXRefs[A](
  final override val length: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends Ref.UnsealedArray0[A] {

  protected[this] type RefT[a] <: Ref[a]

  require(length > 0)

  protected[this] def createRef(initial: A, str: Ref.AllocationStrategy, rig: RefIdGen): RefT[A]

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
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, length)
    this.arr(idx).getImpl
  }

  final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, length)
    this.arr(idx).setImpl(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): RxnImpl[Unit] = {
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, length)
    this.arr(idx).updateImpl(f)
  }

  final override def refs: Chain[RefT[A]] =
    Chain.fromSeq(scala.collection.immutable.ArraySeq.unsafeWrapArray(this.arr))
}

private[choam] final class DenseArrayOfRefs[A](
  size: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends DenseArrayOfXRefs[A](size, initial, str, rig) {

  protected[this] final override type RefT[a] = Ref[a]

  protected[this] def createRef(initial: A, str: Ref.AllocationStrategy, rig: RefIdGen): RefT[A] =
    Ref.unsafe(initial, str, rig)

  protected[this] def refTTag: ClassTag[RefT[A]] =
    ClassTag[Ref[A]](classOf[Ref[A]])
}

private[choam] final class DenseArrayOfTRefs[A](
  size: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends DenseArrayOfXRefs[A](size, initial, str, rig)
  with stm.TArray.UnsealedTArray[A] {

  protected[this] final override type RefT[a] = Ref[a] with stm.TRef[a]

  protected[this] final override def createRef(initial: A, str: Ref.AllocationStrategy, rig: RefIdGen): RefT[A] =
    Ref.unsafeTRef(initial, str, rig)

  protected[this] def refTTag: ClassTag[RefT[A]] =
    ClassTag[RefT[A]](classOf[Ref[A]])

  private[this] final def getOrNull(idx: Int): stm.TRef[A] = {
    if ((idx >= 0) && (idx < size)) {
      this.arr(idx)
    } else {
      null
    }
  }

  final override def get(idx: Int): stm.Txn[Option[A]] = {
    this.getOrNull(idx) match {
      case null => stm.Txn.none[A]
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
}