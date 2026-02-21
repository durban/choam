/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.math.Ordering

import cats.kernel.{ Hash, Order }
import cats.{ InvariantSemigroupal, Show }
import cats.data.State
import cats.effect.kernel.{ Ref => CatsRef }

import internal.mcas.{ MemoryLocation, RefIdGen }
import internal.refs.{ EmptyRefArray, DenseArrayOfRefs, DenseArrayOfTRefs, SparseArrayOfRefs, SparseArrayOfTRefs }

/**
 * A mutable memory location with a pure API and
 * composable lock-free operations.
 *
 * `Ref` is similar to [[java.util.concurrent.atomic.AtomicReference]]
 * or [[cats.effect.kernel.Ref]], but its operations are [[Rxn]]s.
 * Thus, operations on a `Ref` are composable with other [[Rxn]]s.
 */
sealed trait Ref[A] extends RefLike[A] { this: MemoryLocation[A] & core.RefGetAxn[A] =>

  override def asCats[F[_]](implicit F: core.Reactive[F]): CatsRef[F, A] =
    new Ref.CatsRefFromRef[F, A](this) {}

  private[choam] final def loc: MemoryLocation[A] =
    this

  private[choam] def dummy(v: Byte): Long

  private[choam] def getImpl: RxnImpl[A]
  private[choam] def setImpl(a: A): RxnImpl[Unit]
  private[choam] def updateImpl(f: A => A): RxnImpl[Unit]
  private[choam] def updateAndGetImpl(f: A => A): RxnImpl[A]
  private[choam] def modifyImpl[C](f: A => (A, C)): RxnImpl[C]
  private[choam] def flatModifyImpl[C](f: A => (A, Rxn[C])): RxnImpl[C]
}

private[choam] trait UnsealedRef[A] extends Ref[A] { this: MemoryLocation[A] & core.RefGetAxn[A] =>
}

object Ref extends RefInstances0 {

  sealed trait Array[A] {

    def length: Int

    def unsafeGet(idx: Int): Rxn[A]
    def unsafeSet(idx: Int, nv: A): Rxn[Unit]
    def unsafeUpdate(idx: Int)(f: A => A): Rxn[Unit]
    def unsafeModify[B](idx: Int)(f: A => (A, B)): Rxn[B]

    def get(idx: Int): Rxn[Option[A]]
    def set(idx: Int, nv: A): Rxn[Boolean]
    def update(idx: Int)(f: A => A): Rxn[Boolean]
    def modify[B](idx: Int)(f: A => (A, B)): Rxn[Option[B]]

    def refs: IndexedSeq[Ref[A]]

    private[choam] def unsafeFlatModify[B](idx: Int)(f: A => (A, Rxn[B])): Rxn[B]
    private[choam] def getOrCreateRefOrNull(idx: Int): Ref[A]

    private[choam] final def unsafeGetAndSet(idx: Int, nv: A): Rxn[A] = {
      this.getOrCreateRefOrNull(idx) match {
        case null => throw new IndexOutOfBoundsException
        case ref => ref.getAndSet(nv)
      }
    }

    private[choam] final def unsafeGetAndUpdate(idx: Int)(f: A => A): Rxn[A] = {
      this.getOrCreateRefOrNull(idx) match {
        case null => throw new IndexOutOfBoundsException
        case ref => ref.getAndUpdate(f)
      }
    }
  }

  final object Array {

    final def unsafeSwap[A](arr1: Ref.Array[A], idx1: Int, arr2: Ref.Array[A], idx2: Int): Rxn[Unit] = {
      arr1.unsafeGet(idx1).flatMap { o1 =>
        arr2.unsafeModify(idx2) { o2 => (o1, o2) }.flatMap(arr1.unsafeSet(idx1, _))
      }
    }

    private[choam] final def unsafeConsistentRead[A, B](arr1: Ref.Array[A], idx1: Int, arr2: Ref.Array[B], idx2: Int): Rxn[(A, B)] = {
      arr1.unsafeGet(idx1) * arr2.unsafeGet(idx2)
    }
  }

  private[choam] trait UnsealedArray0[A] extends Array[A] // TODO: better name

  private[choam] trait UnsealedArray[A] extends UnsealedArray0[A] {
    protected final def checkIndex(idx: Int): Unit = {
      if ((idx < 0) || (idx >= length)) {
        throw new IndexOutOfBoundsException(s"Index ${idx} out of bounds for length ${length}")
      }
    }
  }

  final def apply[A](initial: A): Rxn[Ref[A]] = {
    apply(initial, AllocationStrategy.Default)
  }

  final def apply[A](initial: A, str: AllocationStrategy): Rxn[Ref[A]] = {
    Rxn.unsafe.delayContext { ctx =>
      Ref.unsafe(initial, str, ctx.refIdGen)
    }
  }

  private[choam] final def tRef[A](initial: A, str: AllocationStrategy = AllocationStrategy.DefaultStm): RxnImpl[Ref[A] with stm.TRef[A]] = {
    Rxn.unsafe.delayContextImpl { ctx =>
      Ref.unsafeTRef(initial, str, ctx.refIdGen)
    }
  }

  // TODO: How to avoid allocating RefArrayRef objects?
  // TODO: Create getAndUpdate(idx: Int, f: A => A) methods.
  // TODO: (Implement them with something like `OffsetMemoryLocation`.)
  // TODO: But: what to do with out-of-bounds indices?
  // TODO: (Refined? But can we avoid boxing?)
  // TODO: Would implementing Traverse help? Probably not.

  final def array[A](size: Int, initial: A): Rxn[Ref.Array[A]] =
    array(size, initial, AllocationStrategy.Default)

  final def array[A](
    size: Int,
    initial: A,
    strategy: AllocationStrategy,
  ): Rxn[Ref.Array[A]] = {
    safeArray(size = size, initial = initial, str = strategy)
  }

  private[choam] final def arrayImpl[A](size: Int, initial: A, strategy: AllocationStrategy): RxnImpl[Ref.Array[A]] = {
    safeArrayImpl(size, initial, strategy)
  }

  private[this] final def safeArray[A](size: Int, initial: A, str: AllocationStrategy): Rxn[Ref.Array[A]] = {
    safeArrayImpl(size, initial, str)
  }

  // the duplicated logic with `unsafeArray` is
  // to avoid having the `if`s inside the `Rxn`:
  private[this] final def safeArrayImpl[A](size: Int, initial: A, str: AllocationStrategy): RxnImpl[Ref.Array[A]] = {
    if (str.stm) {
      safeTArrayImpl(size, initial, str)
    } else {
      if (size > 0) {
        if (str.flat) {
          require(!str.padded, "flat && padded not implemented yet")
          if (str.sparse) Rxn.unsafe.delayContextImpl(ctx => unsafeSparseArray(size, initial, ctx.refIdGen))
          else Rxn.unsafe.delayContextImpl(ctx => unsafeDenseArray(size, initial, ctx.refIdGen))
        } else {
          if (str.sparse) Rxn.unsafe.delayContextImpl(ctx => new SparseArrayOfRefs(size, initial, str, rig = ctx.refIdGen))
          else Rxn.unsafe.delayContextImpl(ctx => new DenseArrayOfRefs(size, initial, str, rig = ctx.refIdGen))
        }
      } else if (size == 0) {
        Rxn.unsafe.delayImpl(new EmptyRefArray[A])
      } else {
        throw new IllegalArgumentException(s"size = ${size}")
      }
    }
  }

  private[choam] final def safeTArrayImpl[A](size: Int, initial: A, str: AllocationStrategy): RxnImpl[Ref.Array[A] with stm.TArray[A]] = {
    require(str.stm)
    if (size > 0) {
      if (str.flat) {
        require(!str.padded, "flat && padded not implemented yet")
        if (str.sparse) Rxn.unsafe.delayContextImpl(ctx => unsafeSparseTArray(size, initial, ctx.refIdGen))
        else Rxn.unsafe.delayContextImpl(ctx => unsafeDenseTArray(size, initial, ctx.refIdGen))
      } else {
        if (str.sparse) Rxn.unsafe.delayContextImpl(ctx => new SparseArrayOfTRefs(size, initial, str, rig = ctx.refIdGen))
        else Rxn.unsafe.delayContextImpl(ctx => new DenseArrayOfTRefs(size, initial, str, rig = ctx.refIdGen))
      }
    } else if (size == 0) {
      Rxn.unsafe.delayImpl(new EmptyRefArray[A])
    } else {
      throw new IllegalArgumentException(s"size = ${size}")
    }
  }

  private[choam] final def unsafeArray[A](size: Int, initial: A, str: AllocationStrategy, rig: RefIdGen): Ref.Array[A] = {
    if (str.stm) {
      unsafeTArray(size, initial, str, rig)
    } else {
      if (size > 0) {
        if (str.flat) {
          require(!str.padded, "flat && padded not implemented yet")
          if (str.sparse) unsafeSparseArray(size, initial, rig)
          else unsafeDenseArray(size, initial, rig)
        } else {
          if (str.sparse) new SparseArrayOfRefs(size, initial, str, rig)
          else new DenseArrayOfRefs(size, initial, str, rig)
        }
      } else if (size == 0) {
        new EmptyRefArray[A]
      } else {
        throw new IllegalArgumentException(s"size = ${size}")
      }
    }
  }

  private[this] final def unsafeTArray[A](size: Int, initial: A, str: AllocationStrategy, rig: RefIdGen): Ref.Array[A] with stm.TArray[A] = {
    require(str.stm)
    if (size > 0) {
      if (str.flat) {
        require(!str.padded, "flat && padded not implemented yet")
        if (str.sparse) unsafeSparseTArray(size, initial, rig)
        else unsafeDenseTArray(size, initial, rig)
      } else {
        if (str.sparse) new SparseArrayOfTRefs(size, initial, str, rig)
        else new DenseArrayOfTRefs(size, initial, str, rig)
      }
    } else if (size == 0) {
      new EmptyRefArray[A]
    } else {
      throw new IllegalArgumentException(s"size = ${size}")
    }
  }

  private[choam] final def catsRefFromRef[F[_] : Reactive, A](ref: Ref[A]): CatsRef[F, A] =
    new CatsRefFromRef[F, A](ref) {}

  private[choam] abstract class CatsRefFromRef[F[_], A](self: Ref[A])(implicit F: Reactive[F])
    extends RefLike.CatsRefFromRefLike[F, A](self)(using F) {

    override def get: F[A] =
      Rxn.unsafe.directRead(self).run[F]
  }

  private[this] final def unsafeDenseArray[A](size: Int, initial: A, rig: RefIdGen): Ref.Array[A] = {
    require(size > 0)
    internal.refs.unsafeNewDenseRefArray[A](size = size, initial = initial)(rig.nextArrayIdBase(size))
  }

  private[this] final def unsafeDenseTArray[A](size: Int, initial: A, rig: RefIdGen): Ref.Array[A] with stm.TArray[A] = {
    require(size > 0)
    internal.refs.unsafeNewDenseRefTArray[A](size = size, initial = initial)(rig.nextArrayIdBase(size))
  }

  private[this] final def unsafeSparseArray[A](size: Int, initial: A, rig: RefIdGen): Ref.Array[A] = {
    require(size > 0)
    internal.refs.unsafeNewSparseRefArray[A](size = size, initial = initial)(rig.nextArrayIdBase(size))
  }

  private[this] final def unsafeSparseTArray[A](size: Int, initial: A, rig: RefIdGen): Ref.Array[A] with stm.TArray[A] = {
    require(size > 0)
    internal.refs.unsafeNewSparseRefTArray[A](size = size, initial = initial)(rig.nextArrayIdBase(size))
  }

  private[choam] final def unsafe[A](initial: A, str: AllocationStrategy, rig: RefIdGen): Ref[A] = {
    unsafeWithId(initial, str, rig.nextId())
  }

  private[choam] final def unsafeTRef[A](initial: A, str: AllocationStrategy, rig: RefIdGen): Ref[A] with stm.TRef[A] = {
    require(str.stm)
    stm.TRef.unsafeRefWithId(initial, rig.nextId()) // TODO: padded TRef
  }

  private[choam] final def unsafeWithId[A](initial: A, str: AllocationStrategy, id: Long): Ref[A] = {
    if (str.stm) {
      // TODO: padded TRef
      stm.TRef.unsafeRefWithId(initial, id)
    } else if (str.padded) {
      internal.refs.unsafeNewRefP1(initial)(id)
    } else {
      internal.refs.unsafeNewRefU1(initial)(id)
    }
  }

  private[choam] final def unsafeUnpaddedWithIdForTesting[A](initial: A, id: Long): Ref[A] = {
    internal.refs.unsafeNewRefU1(initial)(id)
  }

  // Ref2:

  private[choam] final def refP1P1[A, B](a: A, b: B): Rxn[Ref2[A, B]] =
    Ref2.p1p1(a, b)

  private[choam] final def refP2[A, B](a: A, b: B): Rxn[Ref2[A, B]] =
    Ref2.p2(a, b)

  // Utilities:

  final def consistentRead[A, B](ra: Ref[A], rb: Ref[B]): Rxn[(A, B)] = { // TODO:0.5: private?
    ra.get * rb.get
  }

  final def consistentReadMany[A](refs: List[Ref[A]]): Rxn[List[A]] = { // TODO:0.5: private?
    refs.foldRight(Rxn.pure(List.empty[A])) { (ref, acc) =>
      (ref.get * acc).map {
        case (h, t) => h :: t
      }
    }
  }

  final def swap[A](r1: Ref[A], r2: Ref[A]): Rxn[Unit] = {
    r1.get.flatMap { o1 =>
      r2.modify { o2 => (o1, o2) }.flatMap(r1.set)
    }
  }
}

private[core] sealed abstract class RefInstances0 extends RefInstances1 { this: Ref.type =>

  private[this] val _orderingInstance: Ordering[Ref[Any]] = new Ordering[Ref[Any]] {
    final override def compare(x: Ref[Any], y: Ref[Any]): Int =
      MemoryLocation.globalCompare(x.loc, y.loc)
  }

  implicit final def orderingForDevTauriChoamCoreRef[A]: Ordering[Ref[A]] =
    _orderingInstance.asInstanceOf[Ordering[Ref[A]]]
}

private sealed abstract class RefInstances1 extends RefInstances2 { this: Ref.type =>

  private[this] val _orderInstance: Order[Ref[Any]] = new Order[Ref[Any]] {
    final override def compare(x: Ref[Any], y: Ref[Any]): Int =
      MemoryLocation.globalCompare(x.loc, y.loc)
  }

  implicit final def orderForDevTauriChoamCoreRef[A]: Order[Ref[A]] =
    _orderInstance.asInstanceOf[Order[Ref[A]]]
}

private sealed abstract class RefInstances2 extends RefInstances3 { this: Ref.type =>

  private[this] val _hashInstance: Hash[Ref[Any]] =
    Hash.fromUniversalHashCode[Ref[Any]]

  implicit final def hashForDevTauriChoamCoreRef[A]: Hash[Ref[A]] =
    _hashInstance.asInstanceOf[Hash[Ref[A]]]
}

private sealed abstract class RefInstances3 { this: Ref.type =>

  private[this] val _showInstance: Show[Ref[Any]] =
    Show.fromToString

  implicit final def showForDevTauriChoamCoreRef[A]: Show[Ref[A]] =
    _showInstance.asInstanceOf[Show[Ref[A]]]
}

sealed trait RefLike[A] {

  // primitive:

  def get: Rxn[A]

  def modify[B](f: A => (A, B)): Rxn[B]

  // primitive (for performance):

  def set(a: A): Rxn[Unit]

  def update(f: A => A): Rxn[Unit]

  // derived (implemented in RefGetAxn):

  def getAndSet(nv: A): Rxn[A]

  def tryUpdate(f: A => A): Rxn[Boolean]

  def getAndUpdate(f: A => A): Rxn[A]

  def updateAndGet(f: A => A): Rxn[A]

  def tryModify[B](f: A => (A, B)): Rxn[Option[B]]

  def flatModify[B](f: A => (A, Rxn[B])): Rxn[B]

  // interop:

  def asCats[F[_]](implicit F: Reactive[F]): CatsRef[F, A] =
    new RefLike.CatsRefFromRefLike[F, A](this) {}
}

object RefLike {

  implicit final def invariantSemigroupalForDevTauriChoamCoreRefLike: InvariantSemigroupal[RefLike] =
    _invariantSemigroupalInstance

  private[this] val _invariantSemigroupalInstance: InvariantSemigroupal[RefLike] = new InvariantSemigroupal[RefLike] {

    final override def imap[A, B](fa: RefLike[A])(f: A => B)(g: B => A): RefLike[B] = new RefLike[B] {
      final override def get: Rxn[B] = fa.get.map(f)
      final override def modify[X](ff: B => (B, X)): Rxn[X] = fa.modify { a =>
        val bx = ff(f(a))
        (g(bx._1), bx._2)
      }
      final override def set(b: B): Rxn[Unit] = fa.set(g(b))
      final override def update(ff: B => B): Rxn[Unit] = fa.update { a => g(ff(f(a))) }
      final override def getAndSet(b: B): Rxn[B] = fa.getAndSet(g(b)).map(f)
      final override def tryUpdate(ff: B => B): Rxn[Boolean] = fa.tryUpdate { a => g(ff(f(a))) }
      final override def getAndUpdate(ff: B => B): Rxn[B] = fa.getAndUpdate { a => g(ff(f(a))) }.map(f)
      final override def updateAndGet(ff: B => B): Rxn[B] = fa.updateAndGet { a => g(ff(f(a))) }.map(f)
      final override def tryModify[X](ff: B => (B, X)): Rxn[Option[X]] = fa.tryModify { a =>
        val bx = ff(f(a))
        (g(bx._1), bx._2)
      }
      final override def flatModify[X](ff: B => (B, Rxn[X])): Rxn[X] = fa.flatModify { a =>
        val bRxnX = ff(f(a))
        (g(bRxnX._1), bRxnX._2)
      }
    }

    final override def product[A, B](fa: RefLike[A], fb: RefLike[B]): RefLike[(A, B)] = new RefLikeDefaults[(A, B)]  {
      final override def get: Rxn[(A, B)] = fa.get * fb.get
      final override def modify[X](f: ((A, B)) => ((A, B), X)): Rxn[X] = fa.get.flatMap { a =>
        fb.modify { b =>
          val abx = f((a, b))
          (abx._1._2, abx) // we don't actually need the `A` outside, be we avoid 1 extra allocation this way
        }.flatMap { abx => fa.set(abx._1._1).as(abx._2) }
      }
      final override def set(ab: (A, B)): Rxn[Unit] = fa.set(ab._1) *> fb.set(ab._2)
      final override def update(f: ((A, B)) => (A, B)): Rxn[Unit] = fa.get.flatMap { a =>
        fb.modify { b =>
          val ab = f((a, b))
          (ab._2, ab._1)
        }.flatMap(fa.set)
      }
    }
  }

  private[choam] trait UnsealedRefLike[A]
    extends RefLike[A]

  private[choam] final def catsRefFromRefLike[F[_] : Reactive, A](ref: RefLike[A]): CatsRef[F, A] =
    new CatsRefFromRefLike[F, A](ref) {}

  private[core] abstract class CatsRefFromRefLike[F[_], A](self: RefLike[A])(implicit F: Reactive[F])
    extends CatsRef[F, A] {

    def get: F[A] =
      self.get.run[F]

    override def set(a: A): F[Unit] =
      self.set(a).run[F]

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
      self.update(f).run[F]

    override def modify[B](f: A => (A, B)): F[B] =
      self.modify(f).run[F]

    override def tryModifyState[B](state: State[A, B]): F[Option[B]] =
      self.tryModify(a => state.runF.flatMap(_(a)).value).run[F]

    override def modifyState[B](state: State[A,B]): F[B] =
      self.modify(a => state.runF.flatMap(_(a)).value).run[F]
  }
}
