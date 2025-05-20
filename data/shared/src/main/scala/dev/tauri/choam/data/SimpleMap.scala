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
import cats.data.Chain
import cats.collections.HashMap

import core.Rxn

private final class SimpleMap[K, V] private (
  repr: Ref[HashMap[K, V]],
)(implicit K: Hash[K]) extends Map.UnsealedMapExtra[K, V] { self =>

  override def put: Rxn[(K, V), Option[V]] = {
    repr.upd { (m, kv) =>
      (m.updated(kv._1, kv._2), m.get(kv._1))
    }
  }

  override def putIfAbsent: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      m.get(kv._1) match {
        case None =>
          (m.updated(kv._1, kv._2), None)
        case s @ Some(_) =>
          (m, s)
      }
    }
  }

  override def replace: Rxn[(K, V, V), Boolean] = {
    repr.upd[(K, V, V), Boolean] { (m, kvv) =>
      m.get(kvv._1) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, kvv._2) =>
          (m.updated(kvv._1, kvv._3), true)
        case _ =>
          (m, false)
      }
    }
  }

  override def get: Rxn[K, Option[V]] = {
    repr.upd[K, Option[V]] { (m, k) =>
      (m, m.get(k))
    }
  }

  override def del: Rxn[K, Boolean] = {
    repr.upd[K, Boolean] { (m, k) =>
      val newM = m.removed(k)
      (newM, newM.size != m.size)
    }
  }

  override def remove: Rxn[(K, V), Boolean] = {
    repr.upd[(K, V), Boolean] { (m, kv) =>
      m.get(kv._1) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, kv._2) =>
          (m.removed(kv._1), true)
        case _ =>
          (m, false)
      }
    }
  }

  override def clear: Axn[Unit] =
    repr.update(_ => HashMap.empty[K, V])

  override def values(implicit V: Order[V]): Axn[Vector[V]] = {
    repr.get.map { hm =>
      val b = scala.collection.mutable.ArrayBuffer.newBuilder[V]
      b.sizeHint(hm.size)
      val it =  hm.valuesIterator
      while (it.hasNext) {
        b += it.next()
      }
      b.result().sortInPlace()(V.toOrdering).toVector
    }
  }

  final override def keys: Axn[Chain[K]] = {
    repr.get.map { hm =>
      Chain.fromIterableOnce(hm.keysIterator)
    }
  }

  final override def valuesUnsorted: Axn[Chain[V]] = {
    repr.get.map { hm =>
      Chain.fromIterableOnce(hm.valuesIterator)
    }
  }

  final override def items: Axn[Chain[(K, V)]] = {
    repr.get.map { hm =>
      Chain.fromIterableOnce(hm.iterator)
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike.UnsealedRefLike[V] {

    final def get: Axn[V] =
      self.get.provide(key).map(_.getOrElse(default))

    final override def upd[B, C](f: (V, B) => (V, C)): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modify { hm =>
          val currVal = hm.getOrElse(key, default)
          val (newVal, c) = f(currVal, b)
          if (equ(newVal, default)) (hm.removed(key), c)
          else (hm.updated(key, newVal), c)
        }
      }
    }

    final def updWith[B, C](f: (V, B) => Axn[(V, C)]): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modifyWith { hm =>
          val currVal = hm.getOrElse(key, default)
          f(currVal, b).map {
            case (newVal, c) =>
              if (equ(newVal, default)) (hm.removed(key), c)
              else (hm.updated(key, newVal), c)
          }
        }
      }
    }
  }

  private[data] final def unsafeSnapshot: Axn[ScalaMap[K, V]] = {
    repr.get.map { hm =>
      // NB: ScalaMap won't use a custom
      // Hash; this is one reason why
      // this method is `unsafe`.
      val b = ScalaMap.newBuilder[K, V]
      hm.iterator.foreach { entry =>
        b += ((entry._1, entry._2))
      }
      b.result()
    }
  }
}

private object SimpleMap {
  private[data] final def apply[K: Hash, V](str: Ref.AllocationStrategy): Axn[Map.Extra[K, V]] =
    Ref(HashMap.empty[K, V], str).map(new SimpleMap(_))
}
