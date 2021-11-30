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

import cats.effect.kernel.{ Ref => CatsRef }

import mcas.MemoryLocation

/**
 * A mutable memory location with a pure API and
 * composable lock-free operations.
 *
 * `Ref` is similar to [[java.util.concurrent.atomic.AtomicReference]]
 * or [[cats.effect.kernel.Ref]], but its operations are [[Rxn]]s.
 * Thus, operations on a `Ref` are composable with other [[Rxn]]s.
 *
 * However, operations which operate on the same [[Ref]] cannot
 * be composed. The reason for this is that it is not possible to
 * perform conflicting updates atomically. For example, if `r: Ref[Int]`
 * currently contains `42`, and the two operations to update it are
 * `a` to `43`, and `b` to `41`, then performing, e.g., `a * b` is not
 * possible since it would have to simultaneously change `r`'s value to
 * both `43` and `41`. (Currently performing such a [[Rxn]] throws
 * a runtime exception.)
 */
trait Ref[A] extends RefLike[A] { self: MemoryLocation[A] =>

  // TODO: needs better name (it's like `modify`)
  final def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C] =
    Rxn.ref.upd(this)(f)

  // TODO: needs better name (it's like `modifyWith`)
  final def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[B, C] =
    Rxn.ref.updWith(this)(f)

  final def unsafeInvisibleRead: Axn[A] =
    Rxn.unsafe.invisibleRead(this)

  final def unsafeCas(ov: A, nv: A): Axn[Unit] =
    Rxn.unsafe.cas(this, ov, nv)

  private[choam] final def loc: MemoryLocation[A] =
    this

  final def toCats[F[_]](implicit F: Reactive[F]): CatsRef[F, A] =
    new RefLike.CatsRefFromRefLike[F, A](this) {}

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

  def apply[A](initial: A): Axn[Ref[A]] =
    padded(initial)

  def padded[A](initial: A): Axn[Ref[A]] =
    Rxn.unsafe.delay[Any, Ref[A]](_ => Ref.unsafe(initial))

  def unpadded[A](initial: A): Axn[Ref[A]] =
    Rxn.unsafe.delay[Any, Ref[A]](_ => Ref.unsafeUnpadded(initial))

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

  def refP1P1[A, B](a: A, b: B): Axn[refs.Ref2[A, B]] =
    refs.Ref2.p1p1(a, b)

  def refP2[A, B](a: A, b: B): Axn[refs.Ref2[A, B]] =
    refs.Ref2.p2(a, b)
}
