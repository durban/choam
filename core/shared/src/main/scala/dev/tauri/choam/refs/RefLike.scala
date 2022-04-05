/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package refs

import cats.data.State
import cats.effect.kernel.{ Ref => CatsRef }

trait RefLike[A] {

  // abstract:

  def get: Axn[A]

  def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C]

  def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[B, C]

  // derived implementations:

  final def set: Rxn[A, Unit] =
    getAndSet.void

  final def getAndSet: Rxn[A, A] =
    upd[A, A] { (oa, na) => (na, oa) }

  final def update(f: A => A): Axn[Unit] =
    upd[Any, Unit] { (oa, _) => (f(oa), ()) }

  final def updateWith(f: A => Axn[A]): Axn[Unit] =
    updWith[Any, Unit] { (oa, _) => f(oa).map(na => (na, ())) }

  /** Returns `false` iff the update failed */
  final def tryUpdate(f: A => A): Axn[Boolean] =
    update(f).as(true) + Rxn.pure(false)

  /** Returns previous value */
  final def getAndUpdate(f: A => A): Axn[A] =
    upd[Any, A] { (oa, _) => (f(oa), oa) }

  final def getAndUpdateWith(f: A => Axn[A]): Axn[A] =
    updWith[Any, A] { (oa, _) => f(oa).map(na => (na, oa)) }

  /** Returns new value */
  final def updateAndGet(f: A => A): Axn[A] = {
    upd[Any, A] { (oa, _) =>
      val na = f(oa)
      (na, na)
    }
  }

  // TODO: updateAndGetWith OR updateWithAndGet ?

  final def modify[B](f: A => (A, B)): Axn[B] =
    upd[Any, B] { (a, _) => f(a) }

  final def modifyWith[B](f: A => Axn[(A, B)]): Axn[B] =
    updWith[Any, B] { (oa, _) => f(oa) }

  final def tryModify[B](f: A => (A, B)): Axn[Option[B]] =
    modify(f).?

  def toCats[F[_]](implicit F: Reactive[F]): CatsRef[F, A] =
    new RefLike.CatsRefFromRefLike[F, A](this) {}
}

object RefLike {

  def catsRefFromRefLike[F[_] : Reactive, A](ref: RefLike[A]): CatsRef[F, A] =
    new CatsRefFromRefLike[F, A](ref) {}

  private[choam] abstract class CatsRefFromRefLike[F[_], A](self: RefLike[A])(implicit F: Reactive[F])
    extends CatsRef[F, A] {

    def get: F[A] =
      self.get.run[F]

    override def set(a: A): F[Unit] =
      self.set[F](a)

    override def access: F[(A, A => F[Boolean])] = {
      F.monad.flatMap(this.get) { ov =>
        // `access` as defined in cats-effect must never
        // succeed after it was called once, so we need a flag:
        F.monad.map(Ref[Boolean](false).run[F]) { hasBeenCalled =>
          val setter = { (nv: A) =>
            hasBeenCalled.getAndSet.provide(true).flatMapF { wasAlreadyCalled =>
              if (!wasAlreadyCalled) {
                self.modify { currVal =>
                  if (equ(currVal, ov)) (nv, true)
                  else (currVal, false)
                }
              } else {
                Rxn.pure(false)
              }
            }.run[F]
          }
          (ov, setter)
        }
      }
    }

    override def tryUpdate(f: A => A): F[Boolean] =
      self.tryUpdate(f).run[F]

    override def tryModify[B](f: A => (A, B)): F[Option[B]] =
      self.tryModify(f).run[F]

    override def update(f: A => A): F[Unit] =
      self.update(f).run[F]

    override def modify[B](f: A => (A, B)): F[B] =
      self.modify(f).run[F]

    override def tryModifyState[B](state: State[A, B]): F[Option[B]] =
      self.tryModify(a => state.runF.value(a).value).run[F]

    override def modifyState[B](state: State[A,B]): F[B] =
      self.modify(a => state.runF.value(a).value).run[F]
  }
}
