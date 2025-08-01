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

import cats.kernel.Order
import cats.data.Chain
import cats.collections.AvlMap

import core.{ Rxn, Ref, RefLike }

private final class SimpleOrderedMap[K, V] private (
  repr: Ref[AvlMap[K, V]]
)(implicit K: Order[K]) extends Map.UnsealedMapExtra[K, V] { self =>

  final override def put(k: K, v: V): Rxn[Option[V]] = {
    repr.modify[Option[V]] { m =>
      (m + (k, v), m.get(k))
    }
  }

  final override def putIfAbsent(k: K, v: V): Rxn[Option[V]] = {
    repr.modify[Option[V]] { m =>
      m.get(k) match {
        case None =>
          (m + (k, v), None)
        case s @ Some(_) =>
          (m, s)
      }
    }
  }

  final override def replace(k: K, ov: V, nv: V): Rxn[Boolean] = {
    repr.modify[Boolean] { m =>
      m.get(k) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, ov) =>
          (m + ((k, nv)), true)
        case _ =>
          (m, false)
      }
    }
  }

  final override def get(k: K): Rxn[Option[V]] = {
    repr.get.map { m =>
      m.get(k)
    }
  }

  final override def del(k: K): Rxn[Boolean] = {
    repr.modify[Boolean] { m =>
      val newM = m.remove(k)
      val existing = m.get(k)
      (newM, existing.isDefined)
    }
  }

  final override def remove(k: K, v: V): Rxn[Boolean] =  {
    repr.modify[Boolean] { m =>
      m.get(k) match {
        case None =>
          (m, false)
        case Some(cv) if equ(cv, v) =>
          (m.remove(k), true)
        case _ =>
          (m, false)
      }
    }
  }

  final override def clear: Rxn[Unit] =
    repr.update(_ => AvlMap.empty[K, V])

  final override def keys: Rxn[Chain[K]] = {
    repr.get.map { m =>
      Chain.fromSeq(
        m.foldLeft(Vector.newBuilder[K]) { (vb, kv) =>
          vb.addOne(kv._1)
        }.result()
      )
    }
  }

  final override def values: Rxn[Chain[V]] = {
    repr.get.map { m =>
      Chain.fromSeq(
        m.foldLeft(Vector.newBuilder[V]) { (vb, kv) =>
          vb.addOne(kv._2)
        }.result()
      )
    }
  }

  final override def items: Rxn[Chain[(K, V)]] = {
    repr.get.map { m =>
      Chain.fromSeq(
        m.foldLeft(Vector.newBuilder[(K, V)]) { (vb, kv) =>
          vb.addOne(kv)
        }.result()
      )
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike.UnsealedRefLike[V] {

    final def get: Rxn[V] =
      self.get(key).map(_.getOrElse(default))

    final override def set(nv: V): Rxn[Unit] = {
      if (equ(nv, default)) {
        repr.update { am => am.remove(key) }
      } else {
        repr.update { am => am + (key, nv) }
      }
    }

    final override def update(f: V => V): Rxn[Unit] = {
      repr.update { am =>
        val currVal = am.get(key).getOrElse(default)
        val newVal = f(currVal)
        if (equ(newVal, default)) am.remove(key)
        else am + (key, newVal)
      }
    }

    final override def modify[C](f: V => (V, C)): Rxn[C] = {
      repr.modify { am =>
        val currVal = am.get(key).getOrElse(default)
        val (newVal, c) = f(currVal)
        if (equ(newVal, default)) (am.remove(key), c)
        else (am + ((key, newVal)), c)
      }
    }
  }

  private[data] final def unsafeSnapshot: Rxn[ScalaMap[K, V]] = {
    repr.get.map { am =>
      // NB: ScalaMap won't use our
      // Order; this is one reason why
      // this method is `unsafe`.
      val b = ScalaMap.newBuilder[K, V]
      am.foldLeft(()) { (_, kv) =>
        b += (kv)
      }
      b.result()
    }
  }
}

private object SimpleOrderedMap {

  private[data] final def apply[K: Order, V](str: Ref.AllocationStrategy): Rxn[Map.Extra[K, V]] = {
    Ref(AvlMap.empty[K, V], str).map(new SimpleOrderedMap(_))
  }
}
