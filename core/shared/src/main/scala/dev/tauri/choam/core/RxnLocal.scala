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
package core

import java.util.Arrays

import cats.arrow.FunctionK
import cats.Monad

sealed abstract class RxnLocal[G[_], A] private () {
  def get: G[A]
  def set(a: A): G[Unit]
  def update(f: A => A): G[Unit]
  def getAndUpdate(f: A => A): G[A]
}

object RxnLocal {

  sealed abstract class Array[G[_], A] {
    def size: Int
    // TODO: def get(idx: Int): G[Any, Option[A]]
    // TODO: def set(idx: Int, nv: A): G[Any, Boolean]
    def unsafeGet(idx: Int): G[A]
    def unsafeSet(idx: Int, nv: A): G[Unit]
  }

  sealed trait Instances[G[_]] {
    implicit def monadInstance[X]: Monad[G]
  }

  private[this] val _inst: Instances[Rxn] = new Instances[Rxn] {
    final override def monadInstance[X] =
      Rxn.monadInstance
  }

  private[core] final def withLocal[A, I, R](initial: A, body: Rxn.unsafe.WithLocal[A, I, R]): Rxn[R] = {
    Rxn.unsafe.suspend {
      val local = new RxnLocalImpl[A](initial)
      Rxn.internal.newLocal(local) *> body[Rxn](local, FunctionK.id, _inst) <* Rxn.internal.endLocal(local)
    }
  }

  private[core] final def withLocalArray[A, I, R](size: Int, initial: A, body: Rxn.unsafe.WithLocalArray[A, I, R]): Rxn[R] = {
    Rxn.unsafe.suspend {
      val arr = new scala.Array[AnyRef](size)
      Arrays.fill(arr, box(initial))
      val locArr = new RxnLocalArrayImpl[A](arr)
      Rxn.internal.newLocal(locArr) *> body[Rxn](locArr, FunctionK.id, _inst) <* Rxn.internal.endLocal(locArr)
    }
  }

  private[this] final class RxnLocalImpl[A](private[this] var a: A)
    extends RxnLocal[Rxn, A]
    with InternalLocal {
    final override def get: Rxn[A] = Axn.unsafe.delay { this.a }
    final override def set(a: A): Rxn[Unit] = Axn.unsafe.delay { this.a = a }
    final override def update(f: A => A): Rxn[Unit] = Axn.unsafe.delay { this.a = f(this.a) }
    final override def getAndUpdate(f: A => A): Rxn[A] = Axn.unsafe.delay {
      val ov = this.a
      this.a = f(ov)
      ov
    }
    final override def takeSnapshot(): AnyRef = box(this.a)
    final override def loadSnapshot(snap: AnyRef): Unit = {
      this.a = snap.asInstanceOf[A]
    }
  }

  private[this] final class RxnLocalArrayImpl[A](arr: scala.Array[AnyRef])
    extends RxnLocal.Array[Rxn, A]
    with InternalLocal {

    final override def size: Int =
      arr.length

    final override def unsafeGet(idx: Int): Rxn[A] = {
      val arr = this.arr
      internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx = idx, length = arr.length)
      Axn.unsafe.delay { arr(idx).asInstanceOf[A] }
    }

    final override def unsafeSet(idx: Int, nv: A): Rxn[Unit] = {
      val arr = this.arr
      internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx = idx, length = arr.length)
      Axn.unsafe.delay { arr(idx) = box(nv) }
    }

    final override def takeSnapshot(): AnyRef = {
      val arr = this.arr
      Arrays.copyOf(arr, arr.length)
    }

    final override def loadSnapshot(snap: AnyRef): Unit = {
      val snapArr = snap.asInstanceOf[scala.Array[AnyRef]]
      val arr = this.arr
      val len = arr.length
      _assert(snapArr.length == len)
      System.arraycopy(snapArr, 0, arr, 0, len)
    }
  }
}
