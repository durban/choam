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

import scala.math.Ordering

import cats.data.Chain
import cats.kernel.{ Hash, Order }
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
sealed trait Ref[A] extends RefLike.UnsealedRefLike[A] { this: MemoryLocation[A] & core.RefGetAxn[A] =>

  override def asCats[F[_]](implicit F: core.Reactive[F]): CatsRef[F, A] =
    new Ref.CatsRefFromRef[F, A](this) {}

  private[choam] final def loc: MemoryLocation[A] =
    this

  private[choam] def dummy(v: Byte): Long

  private[choam] def getImpl: RxnImpl[A]
  private[choam] def setImpl(a: A): RxnImpl[Unit]
  private[choam] def updateImpl(f: A => A): RxnImpl[Unit]
  private[choam] def modifyImpl[C](f: A => (A, C)): RxnImpl[C]
}

private[choam] trait UnsealedRef[A] extends Ref[A] { this: MemoryLocation[A] & core.RefGetAxn[A] =>
}

object Ref extends RefInstances0 {

  sealed abstract class AllocationStrategy {
    def padded: Boolean
    def withPadded(padded: Boolean): Ref.AllocationStrategy
    def toArrayAllocationStrategy: Array.AllocationStrategy
    private[choam] def stm: Boolean
    private[choam] def withStm(stm: Boolean): Ref.AllocationStrategy
  }

  final object AllocationStrategy {

    final val Default: Ref.AllocationStrategy =
      AllocationStrategy(padded = false)

    final def apply(padded: Boolean): Ref.AllocationStrategy =
      new AllocationStrategyImpl(padded = padded, stm = false)

    private[choam] final val Padded: Ref.AllocationStrategy =
      Ref.AllocationStrategy(padded = true)

    private[choam] final val Unpadded: Ref.AllocationStrategy =
      Ref.AllocationStrategy(padded = false)

    private[choam] final val Stm: Ref.AllocationStrategy =
      new AllocationStrategyImpl(padded = false, stm = true)
  }

  private[this] final class AllocationStrategyImpl(
    final override val padded: Boolean,
    private[choam] final override val stm: Boolean,
  ) extends AllocationStrategy {

    final override def withPadded(padded: Boolean): AllocationStrategy = {
      if (this.padded == padded) this
      else new AllocationStrategyImpl(padded = padded, stm = this.stm)
    }

    private[choam] final override def withStm(stm: Boolean): Ref.AllocationStrategy = {
      if (this.stm == stm) this
      else new AllocationStrategyImpl(padded = this.padded, stm = stm)
    }

    final override def toArrayAllocationStrategy: Array.AllocationStrategy =
      Array.AllocationStrategy.Default.withPadded(this.padded).withStm(this.stm)
  }

  sealed trait Array[A] { // TODO:0.5: revise `Array` API (future-proofing in case we'll have OffsetMemoryLocation)

    def length: Int

    def unsafeGet(idx: Int): Rxn[A]

    def refs: Chain[Ref[A]]

    private[choam] final def unsafeApply(idx: Int): Ref[A] = // TODO: remove this (or only use in tests)
      this.refs.get(idx.toLong).getOrElse(throw new ArrayIndexOutOfBoundsException)
  }

  final object Array {

    sealed abstract class AllocationStrategy extends Ref.AllocationStrategy {

      def sparse: Boolean
      def withSparse(sparse: Boolean): Array.AllocationStrategy
      def flat: Boolean
      def withFlat(flat: Boolean): Array.AllocationStrategy

      override def withPadded(padded: Boolean): Array.AllocationStrategy
      private[choam] override def withStm(stm: Boolean): Array.AllocationStrategy

      final override def toArrayAllocationStrategy: Array.AllocationStrategy =
        this

      private[Ref] final def toInt: Int = {
        var r = 0
        if (this.sparse) {
          r |= 4
        }
        if (this.flat) {
          r |= 2
        }
        if (this.padded) {
          r |= 1
        }
        r
      }
    }

    final object AllocationStrategy {

      final val Default: Array.AllocationStrategy =
        this.apply(sparse = false, flat = true, padded = false)

      private[choam] val SparseFlat: Array.AllocationStrategy =
        this.apply(sparse = true, flat = true, padded = false)

      private[Ref] final val DefaultInt: Int =
        2

      final def apply(sparse: Boolean, flat: Boolean, padded: Boolean): Array.AllocationStrategy =
        new AllocationStrategyImpl(sparse = sparse, flat = flat, padded = padded, stm = false)
    }

    private[this] final class AllocationStrategyImpl (
      final override val sparse: Boolean,
      final override val flat: Boolean,
      final override val padded: Boolean,
      final override val stm: Boolean,
    ) extends AllocationStrategy {

      require(!(padded && flat), "padding is currently not supported for flat = true")

      final override def withSparse(sparse: Boolean): AllocationStrategy =
        this.copy(sparse = sparse)

      final override def withFlat(flat: Boolean): AllocationStrategy =
        this.copy(flat = flat)

      final override def withPadded(padded: Boolean): AllocationStrategy =
        this.copy(padded = padded)

      private[choam] final override def withStm(stm: Boolean): AllocationStrategy =
        this.copy(stm = stm)

      private[this] final def copy(
        sparse: Boolean = this.sparse,
        flat: Boolean = this.flat,
        padded: Boolean = this.padded,
        stm: Boolean = this.stm,
      ): AllocationStrategyImpl = {
        if (
          (sparse == this.sparse) &&
          (flat == this.flat) &&
          (padded == this.padded) &&
          (stm == this.stm)
        ) {
          this
        } else {
          new AllocationStrategyImpl(sparse = sparse, flat = flat, padded = padded, stm = stm)
        }
      }
    }
  }

  private[choam] trait UnsealedArray0[A] extends Array[A] // TODO: better name

  private[choam] trait UnsealedArray[A] extends UnsealedArray0[A] { this: internal.refs.RefIdOnlyN =>

    protected[choam] final override def refToString(): String = {
      val idBase = this.id
      s"Ref.Array[${length}]@${internal.mcas.refHashArrayIdBase(idBase)}"
    }

    protected final def checkIndex(idx: Int): Unit = {
      if ((idx < 0) || (idx >= length)) {
        throw new IndexOutOfBoundsException(s"Index ${idx} out of bounds for length ${length}")
      }
    }
  }

  final def apply[A](initial: A, str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Rxn[Ref[A]] = {
    Rxn.unsafe.delayContext { ctx =>
      Ref.unsafe(initial, str, ctx.refIdGen)
    }
  }

  // TODO: How to avoid allocating RefArrayRef objects?
  // TODO: Create getAndUpdate(idx: Int, f: A => A) methods.
  // TODO: (Implement them with something like `OffsetMemoryLocation`.)
  // TODO: But: what to do with out-of-bounds indices?
  // TODO: (Refined? But can we avoid boxing?)
  // TODO: Would implementing Traverse help? Probably not.

  final def array[A](
    size: Int,
    initial: A,
    strategy: Ref.Array.AllocationStrategy = Ref.Array.AllocationStrategy.Default,
  ): Rxn[Ref.Array[A]] = {
    safeArray(size = size, initial = initial, str = strategy)
  }

  private[choam] final def arrayImpl[A](size: Int, initial: A, strategy: Ref.Array.AllocationStrategy): RxnImpl[Ref.Array[A]] = {
    safeArrayImpl(size, initial, strategy)
  }

  private[this] final def safeArray[A](size: Int, initial: A, str: Ref.Array.AllocationStrategy): Rxn[Ref.Array[A]] = {
    safeArrayImpl(size, initial, str)
  }

  // the duplicated logic with `unsafeArray` is
  // to avoid having the `if`s inside the `Rxn`:
  private[this] final def safeArrayImpl[A](size: Int, initial: A, str: Ref.Array.AllocationStrategy): RxnImpl[Ref.Array[A]] = {
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

  private[choam] final def safeTArrayImpl[A](size: Int, initial: A, str: Ref.Array.AllocationStrategy): RxnImpl[Ref.Array[A] with stm.TArray[A]] = {
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

  private[choam] final def unsafeArray[A](size: Int, initial: A, str: Ref.Array.AllocationStrategy, rig: RefIdGen): Ref.Array[A] = {
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

  private[this] final def unsafeTArray[A](size: Int, initial: A, str: Ref.Array.AllocationStrategy, rig: RefIdGen): Ref.Array[A] with stm.TArray[A] = {
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

  final def consistentRead[A, B](ra: Ref[A], rb: Ref[B]): Rxn[(A, B)] = {
    ra.get * rb.get
  }

  final def consistentReadMany[A](refs: List[Ref[A]]): Rxn[List[A]] = {
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

  implicit final def orderingInstance[A]: Ordering[Ref[A]] =
    _orderingInstance.asInstanceOf[Ordering[Ref[A]]]
}

private sealed abstract class RefInstances1 extends RefInstances2 { this: Ref.type =>

  private[this] val _orderInstance: Order[Ref[Any]] = new Order[Ref[Any]] {
    final override def compare(x: Ref[Any], y: Ref[Any]): Int =
      MemoryLocation.globalCompare(x.loc, y.loc)
  }

  implicit final def orderInstance[A]: Order[Ref[A]] =
    _orderInstance.asInstanceOf[Order[Ref[A]]]
}

private sealed abstract class RefInstances2 { this: Ref.type =>

  private[this] val _hashInstance: Hash[Ref[Any]] =
    Hash.fromUniversalHashCode[Ref[Any]]

  implicit final def hashInstance[A]: Hash[Ref[A]] =
    _hashInstance.asInstanceOf[Hash[Ref[A]]]
}
