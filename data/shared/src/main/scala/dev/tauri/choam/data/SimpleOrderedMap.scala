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

import core.{ =#>, Rxn, Axn, Ref, RefLike }

private final class SimpleOrderedMap[K, V] private (
  repr: Ref[AvlMap[K, V]]
)(implicit K: Order[K]) extends Map.UnsealedMapExtra[K, V] { self =>

  final override def put: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      (m + kv, m.get(kv._1))
    }
  }

  final override def putIfAbsent: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      m.get(kv._1) match {
        case None =>
          (m + kv, None)
        case s @ Some(_) =>
          (m, s)
      }
    }
  }

  final override def replace: Rxn[(K, V, V), Boolean] = {
    repr.upd[(K, V, V), Boolean] { (m, kvv) =>
      m.get(kvv._1) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, kvv._2) =>
          (m + ((kvv._1, kvv._3)), true)
        case _ =>
          (m, false)
      }
    }
  }

  final override def get: Rxn[K, Option[V]] = {
    repr.upd[K, Option[V]] { (m, k) =>
      (m, m.get(k))
    }
  }

  final override def del: Rxn[K, Boolean] = {
    repr.upd[K, Boolean] { (m, k) =>
      val newM = m.remove(k)
      val existing = m.get(k)
      (newM, existing.isDefined)
    }
  }

  final override def remove: Rxn[(K, V), Boolean] =  {
    repr.upd[(K, V), Boolean] { (m, kv) =>
      m.get(kv._1) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, kv._2) =>
          (m.remove(kv._1), true)
        case _ =>
          (m, false)
      }
    }
  }

  final override def clear: Axn[Unit] =
    repr.update(_ => AvlMap.empty[K, V])

  final override def values(implicit V: Order[V]): Axn[Vector[V]] = {
    repr.get.map { am =>
      val b = scala.collection.mutable.ArrayBuffer.newBuilder[V]
      b.sizeHint(am.set.size)
      am.foldLeft(b) { (b, kv) =>
        b += kv._2
      }.result().sortInPlace()(V.toOrdering).toVector
    }
  }

  final override def keys: Axn[Chain[K]] = {
    repr.get.map { m =>
      Chain.fromSeq(
        m.foldLeft(Vector.newBuilder[K]) { (vb, kv) =>
          vb.addOne(kv._1)
        }.result()
      )
    }
  }

  final override def valuesUnsorted: Axn[Chain[V]] = {
    repr.get.map { m =>
      Chain.fromSeq(
        m.foldLeft(Vector.newBuilder[V]) { (vb, kv) =>
          vb.addOne(kv._2)
        }.result()
      )
    }
  }

  final override def items: Axn[Chain[(K, V)]] = {
    repr.get.map { m =>
      Chain.fromSeq(
        m.foldLeft(Vector.newBuilder[(K, V)]) { (vb, kv) =>
          vb.addOne(kv)
        }.result()
      )
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike.UnsealedRefLike[V] {

    final def get: Axn[V] =
      self.get.provide(key).map(_.getOrElse(default))

    final override def set0: Rxn[V, Unit] = {
      repr.update2[V] { (am, nv) =>
        if (equ(nv, default)) {
          am.remove(key)
        } else {
          am + (key, nv)
        }
      }
    }

    final override def set1(nv: V): Axn[Unit] = {
      if (equ(nv, default)) {
        repr.update { am => am.remove(key) }
      } else {
        repr.update { am => am + (key, nv) }
      }
    }

    final override def upd[B, C](f: (V, B) => (V, C)): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modify { am =>
          val currVal = am.get(key).getOrElse(default)
          val (newVal, c) = f(currVal, b)
          if (equ(newVal, default)) (am.remove(key), c)
          else (am + ((key, newVal)), c)
        }
      }
    }

    final def updWith[B, C](f: (V, B) => Axn[(V, C)]): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modifyWith { am =>
          val currVal = am.get(key).getOrElse(default)
          f(currVal, b).map {
            case (newVal, c) =>
              if (equ(newVal, default)) (am.remove(key), c)
              else (am + ((key, newVal)), c)
          }
        }
      }
    }
  }

  private[data] final def unsafeSnapshot: Axn[ScalaMap[K, V]] = {
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

  private[data] final def apply[K: Order, V](str: Ref.AllocationStrategy): Axn[Map.Extra[K, V]] = {
    Ref(AvlMap.empty[K, V], str).map(new SimpleOrderedMap(_))
  }
}
