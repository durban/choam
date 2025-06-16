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

import cats.kernel.{ Hash, Order }
import cats.effect.kernel.{ Ref => CatsRef }

import internal.mcas.{ MemoryLocation, RefIdGen }
import internal.refs.CompatPlatform.AtomicReferenceArray

/**
 * A mutable memory location with a pure API and
 * composable lock-free operations.
 *
 * `Ref` is similar to [[java.util.concurrent.atomic.AtomicReference]]
 * or [[cats.effect.kernel.Ref]], but its operations are [[Rxn]]s.
 * Thus, operations on a `Ref` are composable with other [[Rxn]]s.
 */
sealed trait Ref[A] extends RefLike.UnsealedRefLike[A] { this: MemoryLocation[A] & core.RefGetAxn[A] =>

  final override def get: Axn[A] =
    this

  final override def set0: Rxn[A, Unit] =
    Rxn.ref.updSet0(this)

  final override def set1(a: A): Axn[Unit] =
    Rxn.ref.updSet1(this, a)

  final override def update1(f: A => A): Axn[Unit] =
    Rxn.ref.updUpdate1(this)(f)

  final override def update2[B](f: (A, B) => A): Rxn[B, Unit] =
    Rxn.ref.updUpdate2(this)(f)

  final override def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C] =
    Rxn.ref.upd(this)(f)

  final override def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[B, C] =
    Rxn.ref.updWith(this)(f)

  final override def toCats[F[_]](implicit F: core.Reactive[F]): CatsRef[F, A] =
    new Ref.CatsRefFromRef[F, A](this) {}

  private[choam] final def loc: MemoryLocation[A] =
    this

  private[choam] def dummy(v: Byte): Long
}

private[choam] trait UnsealedRef[A] extends Ref[A] { this: MemoryLocation[A] & core.RefGetAxn[A] =>
}

object Ref extends RefInstances0 {

  final case class AllocationStrategy private (padded: Boolean) {

    final def withPadded(padded: Boolean): AllocationStrategy =
      this.copy(padded = padded)

    final def toArrayAllocationStrategy: Array.AllocationStrategy =
      Array.AllocationStrategy.Default.withPadded(padded = this.padded)
  }

  final object AllocationStrategy {

    final val Default: AllocationStrategy =
      AllocationStrategy(padded = false)

    final def apply(padded: Boolean): AllocationStrategy =
      new AllocationStrategy(padded = padded)

    private[choam] final val Padded: AllocationStrategy =
      new AllocationStrategy(padded = true)
  }

  sealed trait Array[A] {

    def size: Int

    def unsafeGet(idx: Int): Ref[A]

    def apply(idx: Int): Option[Ref[A]]

    final def length: Int =
      this.size
  }

  final object Array {

    final case class AllocationStrategy private ( // TODO:0.5: could be a subtype of Ref.AllocationStrategy
      sparse: Boolean,
      flat: Boolean,
      padded: Boolean,
    ) {

      require(!(padded && flat), "padding is currently not supported for flat = true")

      final def withSparse(sparse: Boolean): AllocationStrategy =
        this.copy(sparse = sparse)

      final def withFlat(flat: Boolean): AllocationStrategy =
        this.copy(flat = flat)

      final def withPadded(padded: Boolean): AllocationStrategy =
        this.copy(padded = padded)

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

      final val Default: AllocationStrategy =
        this.apply(sparse = false, flat = true, padded = false)

      private[choam] val SparseFlat: AllocationStrategy =
        this.apply(sparse = true, flat = true, padded = false)

      private[Ref] final val DefaultInt: Int =
        2

      final def apply(sparse: Boolean, flat: Boolean, padded: Boolean): AllocationStrategy =
        new AllocationStrategy(sparse = sparse, flat = flat, padded = padded)

      final def fromRefAllocationStrategy(ras: Ref.AllocationStrategy): AllocationStrategy =
        ras.toArrayAllocationStrategy
    }
  }

  private[choam] trait UnsealedArray[A] extends Array[A] { this: internal.refs.RefIdOnlyN =>

    protected[choam] final override def refToString(): String = {
      val idBase = this.id
      s"Ref.Array[${size}]@${internal.mcas.refHashString(idBase)}"
    }

    protected final def checkIndex(idx: Int): Unit = {
      if ((idx < 0) || (idx >= size)) {
        throw new IndexOutOfBoundsException(s"Index ${idx} out of bounds for length ${size}")
      }
    }
  }

  private final class EmptyRefArray[A] extends Ref.Array[A] {

    final override val size: Int =
      0

    final override def apply(idx: Int): Option[Ref[A]] =
      None

    final override def unsafeGet(idx: Int): Ref[A] =
      throw new IndexOutOfBoundsException(s"Index ${idx} out of bounds for length 0")

    final override def toString: String =
      s"Ref.Array[0]@${java.lang.Long.toHexString(0L)}"
  }

  final def apply[A](initial: A, str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Axn[Ref[A]] = {
    if (str.padded) padded(initial)
    else unpadded(initial)
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
  ): Axn[Ref.Array[A]] = {
    safeArray(size = size, initial = initial, strategy = strategy.toInt)
  }

  private[choam] final def unsafeArray[A](
    size: Int,
    initial: A,
    strategy: Ref.Array.AllocationStrategy,
    rig: RefIdGen,
  ): Ref.Array[A] = {
    unsafeArray(size, initial, strategy.toInt, rig)
  }

  // the duplicated logic with unsafeArray is to avoid
  // having the `if` and `match` inside the `Axn`:
  private[this] final def safeArray[A](size: Int, initial: A, strategy: Int): Axn[Ref.Array[A]] = {
    if (size > 0) {
      (strategy : @switch) match {
        case 0 => Axn.unsafe.delayContext(ctx => new StrictArrayOfRefs(size, initial, padded = false, rig = ctx.refIdGen))
        case 1 => Axn.unsafe.delayContext(ctx => new StrictArrayOfRefs(size, initial, padded = true, rig = ctx.refIdGen))
        case 2 => Axn.unsafe.delayContext(ctx => unsafeStrictArray(size, initial, ctx.refIdGen))
        case 3 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case 4 => Axn.unsafe.delayContext(ctx => new LazyArrayOfRefs(size, initial, padded = false, rig = ctx.refIdGen))
        case 5 => Axn.unsafe.delayContext(ctx => new LazyArrayOfRefs(size, initial, padded = true, rig = ctx.refIdGen))
        case 6 => Axn.unsafe.delayContext(ctx => unsafeLazyArray(size, initial, ctx.refIdGen))
        case 7 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case _ => throw new IllegalArgumentException(s"invalid strategy: ${strategy}")
      }
    } else if (size == 0) {
      Axn.unsafe.delay(new EmptyRefArray[A])
    } else {
      throw new IllegalArgumentException(s"size = ${size}")
    }
  }

  private[this] final def unsafeArray[A](size: Int, initial: A, strategy: Int, rig: RefIdGen): Ref.Array[A] = {
    if (size > 0) {
      (strategy : @switch) match {
        case 0 => new StrictArrayOfRefs(size, initial, padded = false, rig = rig)
        case 1 => new StrictArrayOfRefs(size, initial, padded = true, rig = rig)
        case 2 => unsafeStrictArray(size, initial, rig)
        case 3 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case 4 => new LazyArrayOfRefs(size, initial, padded = false, rig = rig)
        case 5 => new LazyArrayOfRefs(size, initial, padded = true, rig = rig)
        case 6 => unsafeLazyArray(size, initial, rig)
        case 7 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case _ => throw new IllegalArgumentException(s"invalid strategy: ${strategy}")
      }
    } else if (size == 0) {
      new EmptyRefArray[A]
    } else {
      throw new IllegalArgumentException(s"size = ${size}")
    }
  }

  private[choam] final class StrictArrayOfRefs[A](
    final override val size: Int,
    initial: A,
    padded: Boolean,
    rig: RefIdGen,
  ) extends Ref.Array[A] {

    require(size > 0)

    private[this] val arr: scala.Array[Ref[A]] = {
      val a = new scala.Array[Ref[A]](size)
      var idx = 0
      while (idx < size) {
        a(idx) = if (padded) {
          Ref.unsafePadded(initial, rig)
        } else {
          Ref.unsafeUnpadded(initial, rig)
        }
        idx += 1
      }
      a
    }

    final override def unsafeGet(idx: Int): Ref[A] = {
      internal.refs.CompatPlatform.checkArrayIndexIfScalaJs(idx, size) // TODO: check other places where we might need this
      this.arr(idx)
    }

    final override def apply(idx: Int): Option[Ref[A]] = {
      if ((idx >= 0) && (idx < size)) {
        Some(this.unsafeGet(idx))
      } else {
        None
      }
    }
  }

  private[choam] final class LazyArrayOfRefs[A](
    final override val size: Int,
    initial: A,
    padded: Boolean,
    rig: RefIdGen,
  ) extends Ref.Array[A] {

    require(size > 0)

    private[this] val arr: AtomicReferenceArray[Ref[A]] =
      new AtomicReferenceArray[Ref[A]](size)

    private[this] val idBase: Long =
      rig.nextArrayIdBase(size = size)

    final override def unsafeGet(idx: Int): Ref[A] = {
      val arr = this.arr
      arr.getOpaque(idx) match { // FIXME: reading a `Ref` with a race!
        case null =>
          val nv = if (padded) {
            unsafePaddedWithId(initial, RefIdGen.compute(this.idBase, idx))
          } else {
            unsafeUnpaddedWithId(initial, RefIdGen.compute(this.idBase, idx))
          }
          val wit = arr.compareAndExchange(idx, null, nv)
          if (wit eq null) {
            nv // we're the first
          } else {
            wit // found other
          }
        case ref =>
          ref
      }
    }

    final override def apply(idx: Int): Option[Ref[A]] = {
      if ((idx >= 0) && (idx < size)) {
        Some(this.unsafeGet(idx))
      } else {
        None
      }
    }
  }

  private[choam] final def catsRefFromRef[F[_] : Reactive, A](ref: Ref[A]): CatsRef[F, A] =
    new CatsRefFromRef[F, A](ref) {}

  private[choam] abstract class CatsRefFromRef[F[_], A](self: Ref[A])(implicit F: Reactive[F])
    extends RefLike.CatsRefFromRefLike[F, A](self)(using F) {

    override def get: F[A] =
      Rxn.unsafe.directRead(self).run[F]
  }

  private[this] final def unsafeStrictArray[A](size: Int, initial: A, rig: RefIdGen): Ref.Array[A] = {
    require(size > 0)
    internal.refs.unsafeNewStrictRefArray[A](size = size, initial = initial)(rig.nextArrayIdBase(size))
  }

  private[this] final def unsafeLazyArray[A](size: Int, initial: A, rig: RefIdGen): Ref.Array[A] = {
    require(size > 0)
    internal.refs.unsafeNewSparseRefArray[A](size = size, initial = initial)(rig.nextArrayIdBase(size))
  }

  private[choam] final def padded[A](initial: A): Axn[Ref[A]] =
    Axn.unsafe.delayContext[Ref[A]](ctx => Ref.unsafePadded(initial, ctx.refIdGen))

  private[choam] final def unpadded[A](initial: A): Axn[Ref[A]] =
    Axn.unsafe.delayContext[Ref[A]](ctx => Ref.unsafeUnpadded(initial, ctx.refIdGen))

  private[choam] final def unsafe[A](initial: A, str: AllocationStrategy, rig: RefIdGen): Ref[A] = {
    if (str.padded) unsafePadded(initial, rig)
    else unsafeUnpadded(initial, rig)
  }

  private[choam] final def unsafePadded[A](initial: A, rig: RefIdGen): Ref[A] = {
    unsafePaddedWithId(initial, rig.nextId())
  }

  private[this] final def unsafePaddedWithId[A](initial: A, id: Long): Ref[A] = {
    internal.refs.unsafeNewRefP1(initial)(id)
  }

  private[choam] final def unsafeUnpadded[A](initial: A, rig: RefIdGen): Ref[A] = {
    unsafeUnpaddedWithId(initial, rig.nextId())
  }

  private[choam] final def unsafeUnpaddedWithId[A](initial: A, id: Long): Ref[A] = {
    internal.refs.unsafeNewRefU1(initial)(id)
  }

  // Ref2:

  private[choam] final def refP1P1[A, B](a: A, b: B): Axn[Ref2[A, B]] =
    Ref2.p1p1(a, b)

  private[choam] final def refP2[A, B](a: A, b: B): Axn[Ref2[A, B]] =
    Ref2.p2(a, b)

  // Utilities:

  final def consistentRead[A, B](ra: Ref[A], rb: Ref[B]): Axn[(A, B)] = {
    ra.get * rb.get
  }

  final def consistentReadMany[A](refs: List[Ref[A]]): Axn[List[A]] = {
    refs.foldRight(Rxn.pure(List.empty[A])) { (ref, acc) =>
      (ref.get * acc).map {
        case (h, t) => h :: t
      }
    }
  }

  final def swap[A](r1: Ref[A], r2: Ref[A]): Axn[Unit] = {
    r1.updateWith { o1 =>
      r2.modify[A] { o2 =>
        (o1, o2)
      }
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
