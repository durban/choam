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

import scala.collection.immutable.{ Map => ScalaMap }

import cats.kernel.{ Hash, Order }

import core.Axn

private abstract class AbstractMapPlatform {
  def simpleHashMap[K: Hash, V]: Axn[Map.Extra[K, V]]
  def simpleHashMap[K: Hash, V](str: Ref.AllocationStrategy): Axn[Map.Extra[K, V]]
  def simpleOrderedMap[K: Order, V]: Axn[Map.Extra[K, V]]
  def simpleOrderedMap[K: Order, V](str: Ref.AllocationStrategy): Axn[Map.Extra[K, V]]
  def hashMap[K: Hash, V]: Axn[Map[K, V]]
  def hashMap[K: Hash, V](str: Ref.AllocationStrategy): Axn[Map[K, V]]
  def orderedMap[K: Order, V]: Axn[Map[K, V]]
  def orderedMap[K: Order, V](str: Ref.AllocationStrategy): Axn[Map[K, V]]
  private[data] final def unsafeGetSize[F[_], K, V](m: Map[K, V])(implicit F: Reactive[F]): F[Int] =
    F.monad.map(this.unsafeSnapshot(m))(_.size)
  private[data] def unsafeSnapshot[F[_], K, V](m: Map[K, V])(implicit F: Reactive[F]): F[ScalaMap[K, V]]
}
