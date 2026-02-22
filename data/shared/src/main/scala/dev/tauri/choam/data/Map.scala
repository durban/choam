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
package data

import cats.kernel.{ Eq, Hash, Order }
import cats.{ Invariant, InvariantSemigroupal }
import cats.data.Chain
import cats.syntax.all._
import cats.effect.kernel.{ Ref => CatsRef }
import cats.effect.std.MapRef

import core.{ Rxn, RefLike, Reactive }

sealed trait Map[K, V] { self =>

  // TODO: contains: K =#> Boolean
  // TODO: a variant of `del` could return the old value (if any)
  // TODO: think about a `putIfPresent` (in CSLM this is another overload of `replace`)

  def get(k: K): Rxn[Option[V]]
  def put(k: K, v: V): Rxn[Option[V]]
  def putIfAbsent(k: K, v: V): Rxn[Option[V]]
  def del(k: K): Rxn[Boolean]

  def refLike(key: K, default: V)(implicit V: Eq[V]): RefLike[V]

  def replace(k: K, ov: V, nv: V): Rxn[Boolean] // TODO:0.5: this works with reference eq
  def remove(k: K, v: V): Rxn[Boolean] // TODO:0.5: this works with reference eq

  final def asCats[F[_]](default: V)(implicit F: Reactive[F], V: Eq[V]): MapRef[F, K, V] = { // TODO:0.5: default works with reference eq
    new MapRef[F, K, V] {
      final override def apply(k: K): CatsRef[F, V] =
        self.refLike(k, default).asCats
    }
  }
}

object Map extends MapPlatform {

  sealed trait Extra[K, V] extends Map[K, V] {

    // TODO: type Snapshot
    // TODO: def snapshot: Rxn[Snapshot]

    def clear: Rxn[Unit]

    def keys: Rxn[Chain[K]]

    def values: Rxn[Chain[V]]

    def items: Rxn[Chain[(K, V)]]
  }

  final object Extra {

    implicit final def invariantFunctorForDevTauriChoamDataMapExtra[K]: Invariant[Map.Extra[K, *]] =
      _invariantFunctorInstance.asInstanceOf[Invariant[Extra[K, *]]]

    private[this] val _invariantFunctorInstance: Invariant[Extra[Any, *]] = new Invariant[Extra[Any, *]] {
      final override def imap[A, B](fa: Extra[Any, A])(f: A => B)(g: B => A): Extra[Any, B] = {
        new ImappedMap[Any, A, B](fa, f, g) with Extra[Any, B] {
          final override def clear: Rxn[Unit] = fa.clear
          final override def keys: Rxn[Chain[Any]] = fa.keys
          final override def values: Rxn[Chain[B]] = fa.values.map(_.map(f))
          final override def items: Rxn[Chain[(Any, B)]] = fa.items.map(_.map { case (k, a) => (k, f(a)) })
        }
      }
    }
  }

  private[data] trait UnsealedMap[K, V] extends Map[K, V]

  private[data] trait UnsealedMapExtra[K, V] extends Map.Extra[K, V]

  final override def simpleHashMap[K: Hash, V]: Rxn[Extra[K, V]] =
    SimpleMap[K, V](AllocationStrategy.Default)

  final override def simpleHashMap[K: Hash, V](str: AllocationStrategy): Rxn[Extra[K, V]] =
    SimpleMap[K, V](str)

  final override def simpleOrderedMap[K: Order, V]: Rxn[Extra[K, V]] =
    SimpleOrderedMap[K, V](AllocationStrategy.Default)

  final override def simpleOrderedMap[K: Order, V](str: AllocationStrategy): Rxn[Extra[K, V]] =
    SimpleOrderedMap[K, V](str)

  implicit final def invariantSemigroupalForDevTauriChoamDataMap[K]: InvariantSemigroupal[Map[K, *]] =
    _invariantSemigroupalInstance.asInstanceOf[InvariantSemigroupal[Map[K, *]]]

  private[this] val _invariantSemigroupalInstance: InvariantSemigroupal[Map[Any, *]] = new InvariantSemigroupal[Map[Any, *]] {
    final override def imap[A, B](fa: Map[Any, A])(f: A => B)(g: B => A): Map[Any, B] =
      new ImappedMap[Any, A, B](fa, f, g) {}
    final override def product[A, B](fa: Map[Any, A], fb: Map[Any, B]): Map[Any, (A, B)] =
      new ProductMap[Any, A, B](fa, fb) {}
  }

  private abstract class ImappedMap[K, A, B](fa: Map[K, A], f: A => B, g: B => A) extends Map[K, B] {
    final override def put(k: K, b: B): Rxn[Option[B]] = fa.put(k, g(b)).map(_.map(f))
    final override def putIfAbsent(k: K, b: B): Rxn[Option[B]] = fa.putIfAbsent(k, g(b)).map(_.map(f))
    final override def replace(k: K, ob: B, nb: B): Rxn[Boolean] = fa.replace(k, g(ob), g(nb))
    final override def get(k: K): Rxn[Option[B]] = fa.get(k).map(_.map(f))
    final override def del(k: K): Rxn[Boolean] = fa.del(k)
    final override def remove(k: K, b: B): Rxn[Boolean] = fa.remove(k, g(b))
    final override def refLike(key: K, b: B)(implicit B: Eq[B]): RefLike[B] = {
      fa.refLike(key, g(b))(using Eq.by[A, B](f)).imap(f)(g)
    }
  }

  private class ProductMap[K, A, B](fa: Map[K, A], fb: Map[K, B]) extends Map[K, (A, B)] {
    private[this] final def mergeOptions(opts: (Option[A], Option[B])): Option[(A, B)] =
      opts._1.flatMap { a => opts._2.map(b => (a, b)) }
    private[this] final def mergeBooleans(bools: (Boolean, Boolean)): Boolean =
      bools._1 && bools._2
    final override def put(k: K, ab: (A, B)): Rxn[Option[(A, B)]] =
      (fa.put(k, ab._1) * fb.put(k, ab._2)).map(mergeOptions)
    final override def putIfAbsent(k: K, ab: (A, B)): Rxn[Option[(A, B)]] =
      (fa.putIfAbsent(k, ab._1) * fb.putIfAbsent(k, ab._2)).map(mergeOptions)
    final override def replace(k: K, ov: (A, B), nv: (A, B)): Rxn[Boolean] = {
      fa.replace(k, ov._1, nv._1).flatMap { aWasReplaced =>
        if (aWasReplaced) {
          fb.replace(k, ov._2, nv._2).flatMap { bWasReplaced =>
            if (bWasReplaced) {
              Rxn.true_
            } else { // we have to change back the `A`:
              fa.replace(k, nv._1, ov._1).flatMap { ok =>
                Rxn.unsafe.assert(ok).as(false)
              }
            }
          }
        } else {
          Rxn.false_
        }
      }
    }
    final override def get(k: K): Rxn[Option[(A, B)]] =
      (fa.get(k) * fb.get(k)).map(mergeOptions)
    final override def del(k: K): Rxn[Boolean] =
      (fa.del(k) * fb.del(k)).map(mergeBooleans)
    final override def remove(k: K, ab: (A, B)): Rxn[Boolean] =
      (fa.remove(k, ab._1) * fb.remove(k, ab._2)).map(mergeBooleans)
    final override def refLike(k: K, ab: (A, B))(implicit AB: Eq[(A, B)]): RefLike[(A, B)] = {
      val eqA = Eq.by[A, (A, B)](a => (a, nullOf[B])) // TODO
      val eqB = Eq.by[B, (A, B)](b => (nullOf[A], b)) // TODO
      RefLike.invariantSemigroupalForDevTauriChoamCoreRefLike.product(
        fa.refLike(k, ab._1)(using eqA),
        fb.refLike(k, ab._2)(using eqB),
      )
    }
  }

  private[data] final override def unsafeSnapshot[F[_], K, V](m: Map[K, V])(implicit F: Reactive[F]) = {
    m match {
      case m: SimpleOrderedMap[_, _] =>
        m.unsafeSnapshot.run[F]
      case _ =>
        super.unsafeSnapshot(m)(using F)
    }
  }
}
