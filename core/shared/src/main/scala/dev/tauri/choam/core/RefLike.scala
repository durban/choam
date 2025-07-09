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

import core.{ Rxn, Axn }

import cats.data.State
import cats.effect.kernel.{ Ref => CatsRef }

sealed trait RefLike[A] {

  // primitive:

  def get: Rxn[A]

  def modify[B](f: A => (A, B)): Rxn[B]

  def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[C]

  // primitive (for performance):

  def set1(a: A): Axn[Unit] // TODO: remove

  def set(a: A): Axn[Unit] = set1(a)

  def update1(f: A => A): Axn[Unit]

  // derived:

  final def getAndSet(nv: A): Rxn[A] =
    getAndUpdate { _ => nv }

  @inline
  final def update(f: A => A): Axn[Unit] =
    update1(f)

  final def updateWith(f: A => Axn[A]): Axn[Unit] =
    updWith[Any, Unit] { (oa, _) => f(oa).map(na => (na, ())) }

  /** Returns `false` iff the update failed */
  final def tryUpdate(f: A => A): Axn[Boolean] =
    update1(f).maybe

  /** Returns previous value */
  final def getAndUpdate(f: A => A): Axn[A] =
    modify { oa => (f(oa), oa) }

    /** Returns previous value */
  final def getAndUpdateWith(f: A => Axn[A]): Axn[A] =
    updWith[Any, A] { (oa, _) => f(oa).map(na => (na, oa)) }

  /** Returns new value */
  final def updateAndGet(f: A => A): Axn[A] = {
    modify { oa =>
      val na = f(oa)
      (na, na)
    }
  }

  /** Returns new value */
  final def updateAndGetWith(f: A => Axn[A]): Axn[A] = { // TODO: optimize
    updWith[Any, A] { (oa, _) =>
      f(oa).map { na => (na, na) }
    }
  }

  final def modifyWith[B](f: A => Axn[(A, B)]): Axn[B] =
    updWith[Any, B] { (oa, _) => f(oa) }

  final def tryModify[B](f: A => (A, B)): Axn[Option[B]] =
    modify(f).?

  // interop:

  def toCats[F[_]](implicit F: Reactive[F]): CatsRef[F, A] =
    new RefLike.CatsRefFromRefLike[F, A](this) {}
}

private[choam] object RefLike {

  private[choam] trait UnsealedRefLike[A]
    extends RefLike[A]

  private[choam] final def catsRefFromRefLike[F[_] : Reactive, A](ref: RefLike[A]): CatsRef[F, A] =
    new CatsRefFromRefLike[F, A](ref) {}

  private[core] abstract class CatsRefFromRefLike[F[_], A](self: RefLike[A])(implicit F: Reactive[F])
    extends CatsRef[F, A] {

    def get: F[A] =
      self.get.run[F]

    override def set(a: A): F[Unit] =
      self.set1(a).run[F]

    override def access: F[(A, A => F[Boolean])] = {
      F.monad.map(this.get) { ov =>
        val setter = { (nv: A) =>
          self.modify { currVal =>
            if (equ(currVal, ov)) (nv, true)
            else (currVal, false)
          }.run[F]
        }
        (ov, setter)
      }
    }

    override def tryUpdate(f: A => A): F[Boolean] =
      self.tryUpdate(f).run[F]

    override def tryModify[B](f: A => (A, B)): F[Option[B]] =
      self.tryModify(f).run[F]

    override def update(f: A => A): F[Unit] =
      self.update1(f).run[F]

    override def modify[B](f: A => (A, B)): F[B] =
      self.modify(f).run[F]

    override def tryModifyState[B](state: State[A, B]): F[Option[B]] =
      self.tryModify(a => state.runF.flatMap(_(a)).value).run[F]

    override def modifyState[B](state: State[A,B]): F[B] =
      self.modify(a => state.runF.flatMap(_(a)).value).run[F]
  }
}
