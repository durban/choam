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

import core.{ Rxn, Ref, RefLike }

private final class SimpleMap[K, V] private (
  repr: Ref[HashMap[K, V]],
)(implicit K: Hash[K]) extends Map.UnsealedMapExtra[K, V] { self =>

  override def put(k: K, v: V): Rxn[Option[V]] = {
    repr.modify { m =>
      (m.updated(k, v), m.get(k))
    }
  }

  override def putIfAbsent(k: K, v: V): Rxn[Option[V]] = {
    repr.modify { m =>
      m.get(k) match {
        case None =>
          (m.updated(k, v), None)
        case s @ Some(_) =>
          (m, s)
      }
    }
  }

  override def replace(k: K, ov: V, nv: V): Rxn[Boolean] = {
    repr.modify { m =>
      m.get(k) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, ov) =>
          (m.updated(k, nv), true)
        case _ =>
          (m, false)
      }
    }
  }

  override def get(k: K): Rxn[Option[V]] = {
    repr.get.map { m =>
      m.get(k)
    }
  }

  override def del(k: K): Rxn[Boolean] = {
    repr.modify { m =>
      val newM = m.removed(k)
      (newM, newM.size != m.size)
    }
  }

  override def remove(k: K, v: V): Rxn[Boolean] = {
    repr.modify { m =>
      m.get(k) match {
        case None =>
          (m, false)
        case Some(cv) if equ(cv, v) =>
          (m.removed(k), true)
        case _ =>
          (m, false)
      }
    }
  }

  override def clear: Rxn[Unit] =
    repr.update(_ => HashMap.empty[K, V])

  override def values(implicit V: Order[V]): Rxn[Vector[V]] = {
    repr.get.map { hm =>
      val b = scala.collection.mutable.ArrayBuffer.newBuilder[V]
      b.sizeHint(hm.size)
      val it =  hm.valuesIterator
      while (it.hasNext) {
        b += it.next()
      }
      b.result().sortInPlace()(using V.toOrdering).toVector
    }
  }

  final override def keys: Rxn[Chain[K]] = {
    repr.get.map { hm =>
      Chain.fromIterableOnce(hm.keysIterator)
    }
  }

  final override def valuesUnsorted: Rxn[Chain[V]] = {
    repr.get.map { hm =>
      Chain.fromIterableOnce(hm.valuesIterator)
    }
  }

  final override def items: Rxn[Chain[(K, V)]] = {
    repr.get.map { hm =>
      Chain.fromIterableOnce(hm.iterator)
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike.UnsealedRefLike[V] {

    final def get: Rxn[V] =
      self.get(key).map(_.getOrElse(default))

    final override def set1(nv: V): Rxn[Unit] = {
      if (equ(nv, default)) {
        repr.update { hm => hm.removed(key) }
      } else {
        repr.update { hm => hm.updated(key, nv) }
      }
    }

    final override def update1(f: V => V): Rxn[Unit] = {
      repr.update1 { hm =>
        val currVal = hm.getOrElse(key, default)
        val newVal = f(currVal)
        if (equ(newVal, default)) hm.removed(key)
        else hm.updated(key, newVal)
      }
    }

    final override def modify[C](f: V => (V, C)): Rxn[C] = {
      repr.modify { hm =>
        val currVal = hm.getOrElse(key, default)
        val (newVal, c) = f(currVal)
        if (equ(newVal, default)) (hm.removed(key), c)
        else (hm.updated(key, newVal), c)
      }
    }
  }

  private[data] final def unsafeSnapshot: Rxn[ScalaMap[K, V]] = {
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
  private[data] final def apply[K: Hash, V](str: Ref.AllocationStrategy): Rxn[Map.Extra[K, V]] =
    Ref(HashMap.empty[K, V], str).map(new SimpleMap(_))
}
