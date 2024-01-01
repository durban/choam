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
package data

import cats.kernel.Order
import cats.effect.kernel.{ Ref => CatsRef }
import cats.effect.std.MapRef

import core.Reactive

sealed trait Map[K, V] { self =>

  // TODO: contains: K =#> Boolean
  // TODO: a variant of `del` could return the old value (if any)
  // TODO: think about a `putIfPresent` (in CSLM this is another overload of `replace`)

  def put: Rxn[(K, V), Option[V]]
  def putIfAbsent: Rxn[(K, V), Option[V]]
  def replace: Rxn[(K, V, V), Boolean]
  def get: Rxn[K, Option[V]]
  def del: Rxn[K, Boolean]
  def remove: Rxn[(K, V), Boolean]
  def refLike(key: K, default: V): RefLike[V]

  def toCats[F[_]](default: V)(implicit F: Reactive[F]): MapRef[F, K, V] = {
    new MapRef[F, K, V] {
      final override def apply(k: K): CatsRef[F, V] =
        self.refLike(k, default).toCats
    }
  }
}

object Map extends MapPlatform {

  sealed trait Extra[K, V] extends Map[K, V] {
    def clear: Axn[Unit]
    def values(implicit V: Order[V]): Axn[Vector[V]]
  }

  private[data] trait UnsealedMap[K, V] extends Map[K, V]

  private[data] trait UnsealedMapExtra[K, V] extends Map.Extra[K, V]

  final override def simpleOrderedMap[K: Order, V]: Axn[Extra[K, V]] =
    SimpleOrderedMap[K, V]

  private[data] final override def unsafeSnapshot[F[_], K, V](m: Map[K, V])(implicit F: Reactive[F]) = {
    m match {
      case m: SimpleOrderedMap[_, _] =>
        m.unsafeSnapshot.run[F]
      case _ =>
        super.unsafeSnapshot(m)(F)
    }
  }
}
