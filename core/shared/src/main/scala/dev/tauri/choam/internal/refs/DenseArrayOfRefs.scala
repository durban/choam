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
import mcas.RefIdGen

sealed abstract class DenseArrayOfXRefs[A](
  final override val size: Int,
  initial: A,
  str: Ref.AllocationStrategy,
  rig: RefIdGen,
) extends Ref.UnsealedArray0[A] {

  protected[this] type RefT[a] <: Ref[a]

  require(size > 0)

  protected[this] def createRef(initial: A, str: Ref.AllocationStrategy, rig: RefIdGen): RefT[A]

  protected[this] val arr: scala.Array[RefT[A]] = {
    val a = new scala.Array[Ref[A]](size)
    var idx = 0
    while (idx < size) {
      a(idx) = this.createRef(initial, str, rig)
      idx += 1
    }
    a.asInstanceOf[scala.Array[RefT[A]]]
  }

  final override def unsafeApply(idx: Int): Ref[A] = {
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, size) // TODO: check other places where we might need this
    this.arr(idx)
  }

  final override def apply(idx: Int): Option[Ref[A]] = {
    if ((idx >= 0) && (idx < size)) {
      Some(this.unsafeApply(idx))
    } else {
      None
    }
  }
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

  private[this] final def getOrNull(idx: Int): stm.TRef[A] = {
    if ((idx >= 0) && (idx < size)) {
      this.arr(idx)
    } else {
      null
    }
  }

  final override def unsafeGet(idx: Int): stm.Txn[A] = {
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, size)
    this.arr(idx).get
  }

  final override def unsafeSet(idx: Int, nv: A): stm.Txn[Unit] = {
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, size)
    this.arr(idx).set(nv)
  }

  final override def unsafeUpdate(idx: Int, f: A => A): stm.Txn[Unit] = {
    internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, size)
    this.arr(idx).update(f)
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