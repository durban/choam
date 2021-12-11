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

import cats.kernel.Hash

import org.organicdesign.fp.collections.{ PersistentHashMap, Equator }

private[data] abstract class MapPlatform extends AbstractMapPlatform {

  final override def simple[K: Hash, V]: Axn[Map[K, V]] = Ref[PersistentHashMap[K, V]](
    emptyPhm[K, V]
  ).map { (repr: Ref[PersistentHashMap[K, V]]) =>

    new Map[K, V] {

      override val put: Rxn[(K, V), Option[V]] = {
        repr.upd[(K, V), Option[V]] { (m, kv) =>
          // `m.get` can return `null`
          (m.assoc(kv._1, kv._2), Option(m.get(kv._1)))
        }
      }

      override val putIfAbsent: Rxn[(K, V), Option[V]] = {
        repr.upd[(K, V), Option[V]] { (m, kv) =>
          m.get(kv._1) match {
            case null =>
              (m.assoc(kv._1, kv._2), None)
            case v =>
              (m, Some(v))
          }
        }
      }

      override val replace: Rxn[(K, V, V), Boolean] = {
        repr.upd[(K, V, V), Boolean] { (m, kvv) =>
          m.get(kvv._1) match {
            case null =>
              (m, false)
            case v if equ(v, kvv._2) =>
              (m.assoc(kvv._1, kvv._3), true)
            case _ =>
              (m, false)
          }
        }
      }

      override val get: Rxn[K, Option[V]] = {
        repr.upd[K, Option[V]] { (m, k) =>
          // `m.get` can return `null`
          (m, Option(m.get(k)))
        }
      }

      override val del: Rxn[K, Boolean] = {
        repr.upd[K, Boolean] { (m, k) =>
          val newM = m.without(k)
          (newM, newM.size != m.size)
        }
      }

      override val remove: Rxn[(K, V), Boolean] = {
        repr.upd[(K, V), Boolean] { (m, kv) =>
          m.get(kv._1) match {
            case null =>
              (m, false)
            case v if equ(v, kv._2) =>
              (m.without(kv._1), true)
            case _ =>
              (m, false)
          }
        }
      }

      override val clear: Axn[Unit] =
        repr.update(_ => emptyPhm[K, V])

      final override def values: Axn[Vector[V]] = {
        repr.get.map { phm =>
          val b = Vector.newBuilder[V]
          b.sizeHint(phm.size())
          val it = phm.valIterator()
          while (it.hasNext()) {
            b += it.next()
          }
          b.result()
        }
      }
    }
  }

  private[this] def equatorFromHash[A](implicit A: Hash[A]): Equator[A] = {
    new Equator[A] {
      final override def eq(x: A, y: A): Boolean =
        A.eqv(x, y)
      final override def hash(x: A): Int =
        A.hash(x)
    }
  }

  private[this] def emptyPhm[K: Hash, V]: PersistentHashMap[K, V] =
    PersistentHashMap.empty[K, V](equatorFromHash[K])
}
