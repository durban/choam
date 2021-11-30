/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

trait RefLike[A] {

  // abstract:

  def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C]

  def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[B, C]

  // derived implementations:

  final def get: Axn[A] =
    upd[Any, A] { (oa, _) => (oa, oa) }

  final def getAndSet: Rxn[A, A] =
    upd[A, A] { (oa, na) => (na, oa) }

  final def update(f: A => A): Axn[Unit] =
    upd[Any, Unit] { (oa, _) => (f(oa), ()) }

  final def updateWith(f: A => Axn[A]): Axn[Unit] =
    updWith[Any, Unit] { (oa, _) => f(oa).map(na => (na, ())) }

  /** Returns `false` iff the update failed */
  final def tryUpdate(f: A => A): Axn[Boolean] =
    update(f).as(true) + Rxn.ret(false)

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
}
