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
package unsafe

import java.util.Arrays

import core.{ Rxn, RxnImpl }
import InRxn.InterpState

sealed abstract class RxnLocal[A] private () {
  def get: Rxn[A]
  def set(a: A): Rxn[Unit]
  def update(f: A => A): Rxn[Unit]
  def getAndUpdate(f: A => A): Rxn[A]
}

object RxnLocal {

  private[choam] final class Origin

  sealed abstract class Array[A] {
    def size: Int
    // TODO: def get(idx: Int): G[Any, Option[A]]
    // TODO: def set(idx: Int, nv: A): G[Any, Boolean]
    def unsafeGet(idx: Int): Rxn[A]
    def unsafeSet(idx: Int, nv: A): Rxn[Unit]
  }

  private[choam] final def newLocal[A](initial: A): Rxn[RxnLocal[A]] = {
    newLocalImpl(initial)
  }

  private[choam] final def newTxnLocal[A](initial: A): stm.Txn[stm.TxnLocal[A]] = {
    newLocalImpl(initial)
  }

  private[this] final def newLocalImpl[A](initial: A): RxnImpl[RxnLocalImpl[A]] = {
    Rxn.unsafe.delayContext2Impl { (_, interpState) =>
      val local = new RxnLocalImpl[A](initial, interpState.localOrigin)
      interpState.registerLocal(local)
      local
    }
  }

  private[choam] final def newLocalArray[A](size: Int, initial: A): Rxn[RxnLocal.Array[A]] = {
    newLocalArrayImpl(size, initial)
  }

  private[choam] final def newTxnLocalArray[A](size: Int, initial: A): stm.Txn[stm.TxnLocal.Array[A]] = {
    newLocalArrayImpl(size, initial)
  }

  private[this] final def newLocalArrayImpl[A](size: Int, initial: A): RxnImpl[RxnLocalArrayImpl[A]] = {
    Rxn.unsafe.delayContext2Impl { (_, interpState) =>
      val arr = new scala.Array[AnyRef](size)
      Arrays.fill(arr, box(initial))
      val locArr = new RxnLocalArrayImpl[A](arr, initial, interpState.localOrigin)
      interpState.registerLocal(locArr)
      locArr
    }
  }

  private[this] final class RxnLocalImpl[A](_initial: A, origin: RxnLocal.Origin)
    extends RxnLocal[A]
    with stm.TxnLocal.UnsealedTxnLocal[A]
    with core.InternalLocal {

    private[this] var value: A =
      _initial

    final override def initial: AnyRef =
      box(_initial)

    final override def get: RxnImpl[A] = Rxn.unsafe.delayContext2Impl { (_, interpState) =>
      if (interpState.localOrigin eq origin) {
        this.value
      } else {
        interpState.localGetSlowPath(this).asInstanceOf[A]
      }
    }

    final override def set(a: A): RxnImpl[Unit] = Rxn.unsafe.delayContext2Impl { (_, interpState) =>
      if (interpState.localOrigin eq origin) {
        this.value = a
      } else {
        interpState.localSetSlowPath(this, box(a))
      }
    }

    final override def update(f: A => A): RxnImpl[Unit] = Rxn.unsafe.delayContext2Impl { (_, interpState) =>
      if (interpState.localOrigin eq origin) {
        this.value = f(this.value)
      } else {
        this.getAndUpdateSlowPath(f, interpState) : Unit
      }
    }

    final override def getAndUpdate(f: A => A): RxnImpl[A] = Rxn.unsafe.delayContext2Impl { (_, interpState) =>
      if (interpState.localOrigin eq origin) {
        val ov = this.value
        this.value = f(ov)
        ov
      } else {
        this.getAndUpdateSlowPath(f, interpState)
      }
    }

    private[this] final def getAndUpdateSlowPath(f: A => A, interpState: InterpState): A = {
      val ov: A = interpState.localGetSlowPath(this).asInstanceOf[A]
      interpState.localSetSlowPath(this, box(f(ov)))
      ov
    }

    final override def takeSnapshot(interpState: InterpState): AnyRef = {
      if (interpState.localOrigin eq origin) {
        box(this.value)
      } else {
        interpState.localTakeSnapshotSlowPath(this)
      }
    }

    final override def loadSnapshot(snap: AnyRef, interpState: InterpState): Unit = {
      if (interpState.localOrigin eq origin) {
        this.value = snap.asInstanceOf[A]
      } else {
        interpState.localLoadSnapshotSlowPath(this, snap)
      }
    }
  }

  private[this] final class RxnLocalArrayImpl[A](
    arr: scala.Array[AnyRef],
    _initial: A,
    origin: RxnLocal.Origin,
  ) extends RxnLocal.Array[A]
    with stm.TxnLocal.UnsealedTxnLocalArray[A]
    with core.InternalLocalArray {

    final override def initial: AnyRef =
      box(_initial)

    final override def size: Int =
      arr.length

    final override def unsafeGet(idx: Int): RxnImpl[A] = {
      val arr = this.arr
      Rxn.unsafe.delayContext2Impl { (_, interpState) =>
        if (interpState.localOrigin eq origin) {
          internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx = idx, length = arr.length)
          arr(idx).asInstanceOf[A]
        } else {
          interpState.localGetArrSlowPath(this, idx).asInstanceOf[A]
        }
      }
    }

    final override def unsafeSet(idx: Int, nv: A): RxnImpl[Unit] = {
      val arr = this.arr
      Rxn.unsafe.delayContext2Impl { (_, interpState) =>
        if (interpState.localOrigin eq origin) {
          internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx = idx, length = arr.length)
          arr(idx) = box(nv)
        } else {
          interpState.localSetArrSlowPath(this, idx, box(nv))
        }
      }
    }

    final override def takeSnapshot(interpState: InterpState): AnyRef = {
      if (interpState.localOrigin eq origin) {
        val arr = this.arr
        Arrays.copyOf(arr, arr.length)
      } else {
        interpState.localTakeSnapshotArrSlowPath(this)
      }
    }

    final override def loadSnapshot(snap: AnyRef, interpState: InterpState): Unit = {
      if (interpState.localOrigin eq origin) {
        val snapArr = snap.asInstanceOf[scala.Array[AnyRef]]
        val arr = this.arr
        val len = arr.length
        _assert(snapArr.length == len)
        System.arraycopy(snapArr, 0, arr, 0, len)
      } else {
        interpState.localLoadSnapshotArrSlowPath(this, snap)
      }
    }
  }
}
