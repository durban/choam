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

import java.util.concurrent.ThreadLocalRandom

import scala.math.Ordering

import cats.kernel.{ Hash, Order }
import cats.effect.kernel.{ Ref => CatsRef }

import mcas.MemoryLocation

/**
 * A mutable memory location with a pure API and
 * composable lock-free operations.
 *
 * `Ref` is similar to [[java.util.concurrent.atomic.AtomicReference]]
 * or [[cats.effect.kernel.Ref]], but its operations are [[Rxn]]s.
 * Thus, operations on a `Ref` are composable with other [[Rxn]]s.
 */
trait Ref[A] extends RefLike[A] { self: MemoryLocation[A] =>

  final override def get: Axn[A] =
    Rxn.ref.get(this)

  // TODO: needs better name (it's like `modify`)
  final override def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C] =
    Rxn.ref.upd(this)(f)

  // TODO: needs better name (it's like `modifyWith`)
  final override def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[B, C] =
    Rxn.ref.updWith(this)(f)

  final def unsafeDirectRead: Axn[A] =
    Rxn.unsafe.directRead(this)

  final def unsafeTicketRead: Axn[Rxn.unsafe.Ticket[A]] =
    Rxn.unsafe.ticketRead(this)

  final def unsafeCas(ov: A, nv: A): Axn[Unit] =
    Rxn.unsafe.cas(this, ov, nv)

  final override def toCats[F[_]](implicit F: core.Reactive[F]): CatsRef[F, A] =
    new Ref.CatsRefFromRef[F, A](this) {}

  private[choam] final def loc: MemoryLocation[A] =
    this

  private[choam] def dummy(v: Long): Long
}

object Ref extends RefInstances0 {

  trait Array[A] {
    def size: Int
    def unsafeGet(idx: Int): Ref[A]
    def apply(idx: Int): Option[Ref[A]]
    final def length: Int =
      this.size
  }

  def apply[A](initial: A): Axn[Ref[A]] =
    padded(initial)

  def array[A](size: Int, initial: A): Axn[Ref.Array[A]] =
    Rxn.unsafe.delay(_ => unsafeStrictArray(size, initial))

  def lazyArray[A](size: Int, initial: A): Axn[Ref.Array[A]] =
    Rxn.unsafe.delay(_ => unsafeLazyArray(size, initial))

  def catsRefFromRef[F[_] : Reactive, A](ref: Ref[A]): CatsRef[F, A] =
    new CatsRefFromRef[F, A](ref) {}

  private[choam] abstract class CatsRefFromRef[F[_], A](self: Ref[A])(implicit F: Reactive[F])
    extends RefLike.CatsRefFromRefLike[F, A](self)(F) {

    override def get: F[A] =
      self.unsafeDirectRead.run[F]
  }

  def unsafeStrictArray[A](size: Int, initial: A): Ref.Array[A] = {
    if (size > 0) {
      val tlr = ThreadLocalRandom.current()
      refs.unsafeNewStrictRefArray[A](size = size, initial = initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextInt())
    } else {
      refs.unsafeNewEmptyRefArray[A]()
    }
  }

  def unsafeLazyArray[A](size: Int, initial: A): Ref.Array[A] = {
    if (size > 0) {
      val tlr = ThreadLocalRandom.current()
      refs.unsafeNewLazyRefArray[A](size = size, initial = initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextInt())
    } else {
      refs.unsafeNewEmptyRefArray[A]()
    }
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

  // TODO: do we need this? (We have `forceValidate`.)
  private[choam] final def unsafeWithCurrentVer[A](
    initial: A,
    ctx: mcas.Mcas.ThreadContext,
  ): Ref[A] = {
    // Important: we create a fresh ref, which
    // appears to have been modified when the
    // current global version was committed.
    // This is not the case, but the ref is fresh,
    // so there is no chance of inconsistency.
    // (Except if there are other unsafe things
    // going on.)
    val currentVer: Long = ctx.start().validTs
    assert(mcas.Version.isValid(currentVer))
    val ref = this.unsafe(initial)
    // TODO: We could optimize this, by creating a ctor
    // TODO: for Ref which accepts currentVer; that way
    // TODO: we could avoid this extra CAS (although that
    // TODO: CAS is on a thread-confined object, so it
    // TODO: might already be optimized by the JVM).
    assert(ref.loc.unsafeCasVersionVolatile(mcas.Version.Start, currentVer))
    ref
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
