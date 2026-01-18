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
package data

import cats.kernel.{ Hash, Order }
import cats.data.Chain
import cats.effect.kernel.{ Ref => CatsRef }
import cats.effect.std.MapRef

import core.{ Rxn, RefLike, Reactive }

sealed trait Map[K, V] { self =>

  // TODO: contains: K =#> Boolean
  // TODO: a variant of `del` could return the old value (if any)
  // TODO: think about a `putIfPresent` (in CSLM this is another overload of `replace`)

  def put(k: K, v: V): Rxn[Option[V]]
  def putIfAbsent(k: K, v: V): Rxn[Option[V]]
  def replace(k: K, ov: V, nv: V): Rxn[Boolean]
  def get(k: K): Rxn[Option[V]]
  def del(k: K): Rxn[Boolean]
  def remove(k: K, v: V): Rxn[Boolean]
  def refLike(key: K, default: V): RefLike[V]

  final def asCats[F[_]](default: V)(implicit F: Reactive[F]): MapRef[F, K, V] = {
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

  private[data] final override def unsafeSnapshot[F[_], K, V](m: Map[K, V])(implicit F: Reactive[F]) = {
    m match {
      case m: SimpleOrderedMap[_, _] =>
        m.unsafeSnapshot.run[F]
      case _ =>
        super.unsafeSnapshot(m)(using F)
    }
  }
}
