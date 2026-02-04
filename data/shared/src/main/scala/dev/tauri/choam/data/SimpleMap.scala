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

import scala.collection.immutable.{ Map => ScalaMap, ArraySeq }

import cats.kernel.Hash
import cats.data.Chain
import cats.collections.HashMap

import core.{ Rxn, Ref, RefLike, RefLikeDefaults }

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

  final override def keys: Rxn[Chain[K]] = {
    repr.get.map { hm =>
      val itr = hm.iterator
      val arr = new Array[AnyRef](hm.size)
      var idx = 0
      while (itr.hasNext) {
        arr(idx) = box(itr.next()._1)
        idx += 1
      }
      _assert(idx == arr.length)
      Chain.fromSeq(ArraySeq.unsafeWrapArray(arr).asInstanceOf[ArraySeq[K]])
    }
  }

  final override def values: Rxn[Chain[V]] = {
    repr.get.map { hm =>
      val itr = hm.iterator
      val arr = new Array[AnyRef](hm.size)
      var idx = 0
      while (itr.hasNext) {
        arr(idx) = box(itr.next()._2)
        idx += 1
      }
      _assert(idx == arr.length)
      Chain.fromSeq(ArraySeq.unsafeWrapArray(arr).asInstanceOf[ArraySeq[V]])
    }
  }

  final override def items: Rxn[Chain[(K, V)]] = {
    repr.get.map { hm =>
      val itr = hm.iterator
      val arr = new Array[(K, V)](hm.size)
      var idx = 0
      while (itr.hasNext) {
        arr(idx) = itr.next()
        idx += 1
      }
      _assert(idx == arr.length)
      Chain.fromSeq(ArraySeq.unsafeWrapArray(arr))
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLikeDefaults[V] {

    final def get: Rxn[V] =
      self.get(key).map(_.getOrElse(default))

    final override def set(nv: V): Rxn[Unit] = {
      if (equ(nv, default)) {
        repr.update { hm => hm.removed(key) }
      } else {
        repr.update { hm => hm.updated(key, nv) }
      }
    }

    final override def update(f: V => V): Rxn[Unit] = {
      repr.update { hm =>
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
  private[data] final def apply[K: Hash, V](str: AllocationStrategy): Rxn[Map.Extra[K, V]] =
    Ref(HashMap.empty[K, V], str).map(new SimpleMap(_))
}
