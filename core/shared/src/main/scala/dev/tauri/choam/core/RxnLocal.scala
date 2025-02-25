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

import cats.arrow.ArrowChoice
import cats.Monad

sealed abstract class RxnLocal[G[_, _], A] private () {
  def get: G[Any, A]
  def set(a: A): G[Any, Unit]
  def update(f: A => A): G[Any, Unit]
}

object RxnLocal {

  sealed trait Instances[G[_, _]] {
    implicit def monadInstance[X]: Monad[G[X, *]]
    implicit def arrowChoiceInstance: ArrowChoice[G]
  }

  private[this] val _inst: Instances[Rxn] = new Instances[Rxn] {
    final override def monadInstance[X] =
      Rxn.monadInstance
    final override def arrowChoiceInstance =
      Rxn.arrowChoiceInstance
  }

  sealed trait Lift[F[_, _], G[_, _]] {
    def apply[A, B](fab: F[A, B]): G[A, B]
  }

  private[this] val _idLift: Lift[Rxn, Rxn] = new Lift[Rxn, Rxn] {
    final override def apply[A, B](r: Rxn[A, B]): Rxn[A, B] = r
  }

  private[core] final def withLocal[A, I, R](initial: A, body: Rxn.WithLocal[A, I, R]): Rxn[I, R] = {
    Rxn.unsafe.suspend {
      val local = new RxnLocalImpl[A](initial)
      body[Rxn](local, _idLift, _inst)
    }
  }

  private[this] final class RxnLocalImpl[A](private[this] var a: A) extends RxnLocal[Rxn, A] {
    final override def get: Rxn[Any, A] = Axn.unsafe.delay { this.a }
    final override def set(a: A): Rxn[Any, Unit] = Axn.unsafe.delay { this.a = a }
    final override def update(f: A => A): Rxn[Any, Unit] = Axn.unsafe.delay { this.a = f(this.a) }
  }
}
