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

import scala.math.Ordering

import cats.kernel.{ Order, Hash }
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
    kcas.KCAS.debugRead(this)
  }

  private[choam] def dummy(v: Long): Long
}

object Ref extends RefInstances0 {

  trait Array[A] {
    def size: Int
    def apply(idx: Int): Ref[A] // TODO: throws if `idx` is bad
  }

  def apply[A](initial: A): Axn[Ref[A]] =
    padded(initial)

  def array[A](size: Int, initial: A): Axn[Ref.Array[A]] =
    Rxn.unsafe.delay(_ => unsafeArray(size, initial))

  def unsafeArray[A](size: Int, initial: A): Ref.Array[A] = {
    val tlr = ThreadLocalRandom.current()
    refs.unsafeNewRefArray[A](size = size, initial = initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextInt())
  }

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

private[choam] sealed abstract class RefInstances0 extends RefInstances1 { this: Ref.type =>

  private[this] val _orderingInstance: Ordering[Ref[Any]] = new Ordering[Ref[Any]] {
    final override def compare(x: Ref[Any], y: Ref[Any]): Int =
      MemoryLocation.globalCompare(x.loc, y.loc)
  }

  implicit final def orderingInstance[A]: Ordering[Ref[A]] =
    _orderingInstance.asInstanceOf[Ordering[Ref[A]]]
}

private[choam] sealed abstract class RefInstances1 extends RefInstances2 { this: Ref.type =>

  private[this] val _orderInstance: Order[Ref[Any]] = new Order[Ref[Any]] {
    final override def compare(x: Ref[Any], y: Ref[Any]): Int =
      MemoryLocation.globalCompare(x.loc, y.loc)
  }

  implicit final def orderInstance[A]: Order[Ref[A]] =
    _orderInstance.asInstanceOf[Order[Ref[A]]]
}

private[choam] sealed abstract class RefInstances2 { this: Ref.type =>

  private[this] val _hashInstance: Hash[Ref[Any]] =
    Hash.fromUniversalHashCode[Ref[Any]]

  implicit final def hashInstance[A]: Hash[Ref[A]] =
    _hashInstance.asInstanceOf[Hash[Ref[A]]]
}
