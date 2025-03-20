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

import cats.{ ~>, Monad }

// Note: not really private, published in dev.tauri.choam.stm
private[choam] sealed abstract class TxnLocal[G[_], A] private () {
  def get: G[A]
  def set(a: A): G[Unit]
  def update(f: A => A): G[Unit]
  def getAndUpdate(f: A => A): G[A]
}

// Note: not really private, published in dev.tauri.choam.stm
private[choam] object TxnLocal {

  sealed trait Instances[G[_]] {
    implicit def monadInstance: Monad[G]
  }

  private[this] val _inst: Instances[Txn] = new Instances[Txn] {
    final override def monadInstance: Monad[Txn] =
      Txn.monadInstance
  }

  private[this] val _idLift: Txn ~> Txn =
    cats.arrow.FunctionK.id

  private[core] final def withLocal[A, R](initial: A, body: Txn.unsafe.WithLocal[A, R]): Txn[R] = {
    Txn.unsafe.suspend {
      val local = new TxnLocalImpl(initial)
      Rxn.internal.newLocal(local) *> body[Txn](local, _idLift, _inst) <* Rxn.internal.endLocal(local)
    }
  }

  private[this] final class TxnLocalImpl[A](private[this] var a: A)
    extends TxnLocal[Txn, A]
    with InternalLocal {
    final override def get: Txn[A] = Txn.unsafe.delay { this.a }
    final override def set(a: A): Txn[Unit] = Txn.unsafe.delay { this.a = a }
    final override def update(f: A => A): Txn[Unit] = Txn.unsafe.delay { this.a = f(this.a) }

    final override def getAndUpdate(f: A => A): Txn[A] = Txn.unsafe.delay {
      val ov = this.a
      this.a = f(ov)
      ov
    }
    final override def takeSnapshot(): AnyRef = box(this.a)
    final override def loadSnapshot(snap: AnyRef): Unit = {
      this.a = snap.asInstanceOf[A]
    }
  }
}
