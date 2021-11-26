/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.collection.immutable

// TODO: use Eq and Hash
trait Map[K, V] {
  def put: Rxn[(K, V), Option[V]]
  def putIfAbsent: Rxn[(K, V), Option[V]]
  def replace: Rxn[(K, V, V), Boolean]
  def get: Rxn[K, Option[V]]
  def del: Rxn[K, Boolean]
  def remove: Rxn[(K, V), Boolean]
  def snapshot: Axn[immutable.Map[K, V]]
  def clear: Axn[immutable.Map[K, V]]
}

object Map {

  def simple[K, V]: Axn[Map[K, V]] = Rxn.unsafe.delay { _ =>
    new Map[K, V] {

      private[this] val repr: Ref[immutable.Map[K, V]] =
        Ref.unsafe(immutable.Map.empty)

      override val put: Rxn[(K, V), Option[V]] = {
        repr.upd[(K, V), Option[V]] { (m, kv) =>
          (m + kv, m.get(kv._1))
        }
      }

      override val putIfAbsent: Rxn[(K, V), Option[V]] = {
        repr.upd[(K, V), Option[V]] { (m, kv) =>
          m.get(kv._1) match {
            case s @ Some(_) =>
              (m, s)
            case None =>
              (m + kv, None)
          }
        }
      }

      override val replace: Rxn[(K, V, V), Boolean] = {
        repr.upd[(K, V, V), Boolean] { (m, kvv) =>
          m.get(kvv._1) match {
            case Some(v) if equ(v, kvv._2) =>
              (m + (kvv._1 -> kvv._3), true)
            case _ =>
              (m, false)
          }
        }
      }

      override val get: Rxn[K, Option[V]] = {
        repr.upd[K, Option[V]] { (m, k) =>
          (m, m.get(k))
        }
      }

      override val del: Rxn[K, Boolean] = {
        repr.upd[K, Boolean] { (m, k) =>
          val newM = m - k
          (newM, newM.size != m.size)
        }
      }

      override val remove: Rxn[(K, V), Boolean] = {
        repr.upd[(K, V), Boolean] { (m, kv) =>
          m.get(kv._1) match {
            case Some(v) if equ(v, kv._2) =>
              (m - kv._1, true)
            case _ =>
              (m, false)
          }
        }
      }

      override val snapshot =
        repr.get

      override val clear =
        repr.getAndUpdate(_ => immutable.Map.empty)
    }
  }
}
