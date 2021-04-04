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

import java.util.concurrent.ThreadLocalRandom

import mcas.MemoryLocation

/**
 * Atomic reference with composable lock-free operations.
 *
 * `Ref` is similar to [[java.util.concurrent.atomic.AtomicReference]]
 * or [[cats.effect.kernel.Ref]], but its operations are [[Reaction]]s.
 * Thus, operations on a `Ref` are composable with other [[Reaction]]s.
 */
trait Ref[A] extends MemoryLocation[A] {

  // TODO: that this extends MemoryLocation should be an impl. detail

  final def get: Action[A] =
    upd[Any, A] { (oa, _) => (oa, oa) }

  final def getAndSet: Reaction[A, A] =
    upd[A, A] { (oa, na) => (na, oa) }

  final def update(f: A => A): Action[Unit] =
    upd[Any, Unit] { (oa, _) => (f(oa), ()) }

  final def updateWith(f: A => Action[A]): Action[Unit] =
    updWith[Any, Unit] { (oa, _) => f(oa).map(na => (na, ())) }

  /** Returns `false` iff the update failed */
  final def tryUpdate(f: A => A): Action[Boolean] =
    update(f).?.map(_.isDefined)

  /** Returns previous value */
  final def getAndUpdate(f: A => A): Action[A] =
    upd[Any, A] { (oa, _) => (f(oa), oa) }

  final def getAndUpdateWith(f: A => Action[A]): Action[A] =
    updWith[Any, A] { (oa, _) => f(oa).map(na => (na, oa)) }

  /** Returns new value */
  final def updateAndGet(f: A => A): Action[A] = {
    upd[Any, A] { (oa, _) =>
      val na = f(oa)
      (na, na)
    }
  }

  // TODO: updateAndGetWith OR updateWithAndGet ?

  final def modify[B](f: A => (A, B)): Action[B] =
    upd[Any, B] { (a, _) => f(a) }

  final def modifyWith[B](f: A => Action[(A, B)]): Action[B] =
    updWith[Any, B] { (oa, _) => f(oa) }

  final def tryModify[B](f: A => (A, B)): Action[Option[B]] =
    modify(f).?

  // TODO: how to call this? It's like `modify`...
  final def upd[B, C](f: (A, B) => (A, C)): React[B, C] =
    React.upd(this)(f)

  // TODO: how to call this? It's like `modifyWith`...
  final def updWith[B, C](f: (A, B) => Action[(A, C)]): React[B, C] =
    React.updWith(this)(f)

  final def unsafeInvisibleRead: React[Any, A] =
    React.unsafe.invisibleRead(this)

  final def unsafeCas(ov: A, nv: A): React[Any, Unit] =
    React.unsafe.cas(this, ov, nv)

  /** For testing */
  private[choam] final def debugRead(): A = {
    this.unsafeGetVolatile() match {
      case null =>
        kcas.NaiveKCAS.read(this, kcas.NaiveKCAS.currentContext())
      case _: kcas.WordDescriptor[_] =>
        kcas.EMCAS.read(this, kcas.EMCAS.currentContext())
      case a =>
        a
    }
  }

  private[choam] def dummy(v: Long): Long
}

object Ref {

  def apply[A](initial: A): Action[Ref[A]] =
    padded(initial)

  def padded[A](initial: A): Action[Ref[A]] =
    React.delay[Any, Ref[A]](_ => Ref.unsafe(initial))

  def unpadded[A](initial: A): Action[Ref[A]] =
    React.delay[Any, Ref[A]](_ => Ref.unsafeUnpadded(initial))

  def unsafe[A](initial: A): Ref[A] =
    unsafePadded(initial)

  def unsafePadded[A](initial: A): Ref[A] = {
    val tlr = ThreadLocalRandom.current()
    unsafeWithId(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  def unsafeUnpadded[A](initial: A): Ref[A] = {
    val tlr = ThreadLocalRandom.current()
    refs.unsafeNewRefU1(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  /** Only for testing/benchmarks */
  private[choam] def unsafeWithId[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] =
    refs.unsafeNewRefP1(initial)(i0, i1, i2, i3)

  // Ref2:

  def refP1P1[A, B](a: A, b: B): Action[refs.Ref2[A, B]] =
    refs.Ref2.p1p1(a, b)

  def refP2[A, B](a: A, b: B): Action[refs.Ref2[A, B]] =
    refs.Ref2.p2(a, b)
}
