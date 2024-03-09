/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import internal.mcas.MemoryLocation
import CompatPlatform.AtomicReferenceArray

/**
 * A mutable memory location with a pure API and
 * composable lock-free operations.
 *
 * `Ref` is similar to [[java.util.concurrent.atomic.AtomicReference]]
 * or [[cats.effect.kernel.Ref]], but its operations are [[Rxn]]s.
 * Thus, operations on a `Ref` are composable with other [[Rxn]]s.
 */
sealed trait Ref[A] extends RefLike[A] { this: MemoryLocation[A] =>

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

private[refs] trait UnsealedRef[A] extends Ref[A] { this: MemoryLocation[A] =>
}

object Ref extends RefInstances0 {

  sealed trait Array[A] {

    def size: Int

    def unsafeGet(idx: Int): Ref[A]

    def apply(idx: Int): Option[Ref[A]]

    final def length: Int =
      this.size
  }

  final object Array {

    final case class AllocationStrategy private (
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
    }
  }

  private[refs] trait UnsealedArray[A] extends Array[A] { this: RefIdOnly =>

    protected[refs] final override def refToString(): String = {
      val h = (id0 ^ id1 ^ id2 ^ id3) & (~0xffffL)
      s"Ref.Array[${size}]@${java.lang.Long.toHexString(h >> 16)}"
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

  private[choam] final def apply[A](initial: A): Axn[Ref[A]] =
    padded(initial)

  // TODO: How to avoid allocating RefArrayRef objects?
  // TODO: Create getAndUpdate(idx: Int, f: A => A) methods.
  // TODO: But: what to do with out-of-bounds indices?
  // TODO: (Refined? But can we avoid boxing?)
  // TODO: Would implementing Traverse help? Probably not.

  final def array[A](size: Int, initial: A): Axn[Ref.Array[A]] = {
    safeArray(size = size, initial = initial, strategy = Ref.Array.AllocationStrategy.DefaultInt)
  }

  final def array[A](size: Int, initial: A, strategy: Ref.Array.AllocationStrategy): Axn[Ref.Array[A]] = {
    safeArray(size = size, initial = initial, strategy = strategy.toInt)
  }

  private[choam] final def unsafeArray[A](size: Int, initial: A, strategy: Ref.Array.AllocationStrategy): Ref.Array[A] = {
    unsafeArray(size, initial, strategy.toInt)
  }

  // the duplicated logic with unsafeArray is to avoid
  // having the `if` and `match` inside the `Axn`:
  private[this] final def safeArray[A](size: Int, initial: A, strategy: Int): Axn[Ref.Array[A]] = {
    if (size > 0) {
      (strategy : @switch) match {
        case 0 => Axn.unsafe.delay(new StrictArrayOfRefs(size, initial, padded = false))
        case 1 => Axn.unsafe.delay(new StrictArrayOfRefs(size, initial, padded = true))
        case 2 => Axn.unsafe.delay(unsafeStrictArray(size, initial))
        case 3 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case 4 => Axn.unsafe.delay(new LazyArrayOfRefs(size, initial, padded = false))
        case 5 => Axn.unsafe.delay(new LazyArrayOfRefs(size, initial, padded = true))
        case 6 => Axn.unsafe.delay(unsafeLazyArray(size, initial))
        case 7 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case _ => throw new IllegalArgumentException(s"invalid strategy: ${strategy}")
      }
    } else if (size == 0) {
      Axn.unsafe.delay(new EmptyRefArray[A])
    } else {
      throw new IllegalArgumentException(s"size = ${size}")
    }
  }

  private[this] final def unsafeArray[A](size: Int, initial: A, strategy: Int): Ref.Array[A] = {
    if (size > 0) {
      (strategy : @switch) match {
        case 0 => new StrictArrayOfRefs(size, initial, padded = false)
        case 1 => new StrictArrayOfRefs(size, initial, padded = true)
        case 2 => unsafeStrictArray(size, initial)
        case 3 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case 4 => new LazyArrayOfRefs(size, initial, padded = false)
        case 5 => new LazyArrayOfRefs(size, initial, padded = true)
        case 6 => unsafeLazyArray(size, initial)
        case 7 => throw new IllegalArgumentException("flat && padded not implemented yet")
        case _ => throw new IllegalArgumentException(s"invalid strategy: ${strategy}")
      }
    } else if (size == 0) {
      new EmptyRefArray[A]
    } else {
      throw new IllegalArgumentException(s"size = ${size}")
    }
  }

  private[refs] final class StrictArrayOfRefs[A](
    final override val size: Int,
    initial: A,
    padded: Boolean
  ) extends Ref.Array[A] {

    require(size > 0)

    private[this] val arr: scala.Array[Ref[A]] = {
      val a = new scala.Array[Ref[A]](size)
      var idx = 0
      while (idx < size) {
        a(idx) = if (padded) {
          Ref.unsafePadded(initial)
        } else {
          Ref.unsafeUnpadded(initial)
        }
        idx += 1
      }
      a
    }

    final override def unsafeGet(idx: Int): Ref[A] = {
      CompatPlatform.checkArrayIndexIfScalaJs(idx, size) // TODO: check other places where we might need this
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

  private[refs] final class LazyArrayOfRefs[A](
    final override val size: Int,
    initial: A,
    padded: Boolean,
  ) extends Ref.Array[A] {

    require(size > 0)

    private[this] val arr: AtomicReferenceArray[Ref[A]] =
      new AtomicReferenceArray[Ref[A]](size)

    final override def unsafeGet(idx: Int): Ref[A] = {
      val arr = this.arr
      arr.getOpaque(idx) match { // FIXME: reading a `Ref` with a race!
        case null =>
          val nv = if (padded) {
            Ref.unsafePadded(initial)
          } else {
            Ref.unsafeUnpadded(initial)
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

  private[refs] abstract class CatsRefFromRef[F[_], A](self: Ref[A])(implicit F: Reactive[F])
    extends RefLike.CatsRefFromRefLike[F, A](self)(F) {

    override def get: F[A] =
      self.unsafeDirectRead.run[F]
  }

  private[refs] final def unsafeStrictArray[A](size: Int, initial: A): Ref.Array[A] = {
    require(size > 0)
    val tlr = ThreadLocalRandom.current()
    unsafeNewStrictRefArray[A](size = size, initial = initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextInt())
  }

  private[refs] final def unsafeLazyArray[A](size: Int, initial: A): Ref.Array[A] = {
    require(size > 0)
    val tlr = ThreadLocalRandom.current()
    unsafeNewSparseRefArray[A](size = size, initial = initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextInt())
  }

  final def padded[A](initial: A): Axn[Ref[A]] =
    Axn.unsafe.delay[Ref[A]](Ref.unsafePadded(initial))

  final def unpadded[A](initial: A): Axn[Ref[A]] =
    Axn.unsafe.delay[Ref[A]](Ref.unsafeUnpadded(initial))

  private[choam] final def unsafe[A](initial: A): Ref[A] =
    unsafePadded(initial)

  private[choam] final def unsafePadded[A](initial: A): Ref[A] = {
    val tlr = ThreadLocalRandom.current()
    unsafeWithId(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  private[choam] final def unsafeUnpadded[A](initial: A): Ref[A] = {
    val tlr = ThreadLocalRandom.current()
    unsafeNewRefU1(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  /** Only for testing/benchmarks */
  private[choam] def unsafeWithId[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] =
    unsafeNewRefP1(initial)(i0, i1, i2, i3)

  // Ref2:

  def refP1P1[A, B](a: A, b: B): Axn[Ref2[A, B]] =
    Ref2.p1p1(a, b)

  def refP2[A, B](a: A, b: B): Axn[Ref2[A, B]] =
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

private[refs] sealed abstract class RefInstances0 extends RefInstances1 { this: Ref.type =>

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
