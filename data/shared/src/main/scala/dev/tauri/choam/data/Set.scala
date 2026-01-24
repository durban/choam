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

import cats.kernel.{ Hash, Order }

import core.Rxn

sealed trait Set[A] {

  def contains(a: A): Rxn[Boolean]

  /** @return `true` iff it did not already contain the element */
  def add(a: A): Rxn[Boolean]

  /** @return `true` iff it did contain the element */
  def remove(a: A): Rxn[Boolean]
}

object Set {

  final def hashSet[A](implicit A: Hash[A]): Rxn[Set[A]] =
    Map.hashMap[A, Unit].map(new SetFromMap(_))

  final def hashSet[A](str: AllocationStrategy)(implicit A: Hash[A]): Rxn[Set[A]] =
    Map.hashMap[A, Unit](str).map(new SetFromMap(_))

  final def orderedSet[A](implicit A: Order[A]): Rxn[Set[A]] =
    Map.orderedMap[A, Unit].map(new SetFromMap(_))

  final def orderedSet[A](str: AllocationStrategy)(implicit A: Order[A]): Rxn[Set[A]] =
    Map.orderedMap[A, Unit](str).map(new SetFromMap(_))

  private[this] final class SetFromMap[A](m: Map[A, Unit]) extends Set[A] {
    final override def contains(a: A): Rxn[Boolean] =
      m.get(a).map(_.isDefined)
    final override def add(a: A): Rxn[Boolean] =
      m.putIfAbsent(a, ()).map(_.isEmpty)
    final override def remove(a: A): Rxn[Boolean] =
      m.del(a)
  }
}
