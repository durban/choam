/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import org.organicdesign.fp.collections.{ PersistentHashMap, Equator }
import org.organicdesign.fp.collections.UnmodMap.UnEntry
import org.organicdesign.fp.oneOf.{ Option => POption }

private final class SimpleMap[K, V] private (
  repr: Ref[PersistentHashMap[K, V]],
)(implicit K: Hash[K]) extends Map.UnsealedMapExtra[K, V] { self =>

  private[this] final def valueOptionFromEntry(e: POption[UnEntry[K, V]]): Option[V] = {
    if (e.isSome()) Some(e.get().getValue()) else None
  }

  override val put: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      (m.assoc(kv._1, kv._2), valueOptionFromEntry(m.entry(kv._1)))
    }
  }

  override val putIfAbsent: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      val e = m.entry(kv._1)
      if (e.isSome()) {
        (m, Some(e.get().getValue()))
      } else {
        (m.assoc(kv._1, kv._2), None)
      }
    }
  }

  override val replace: Rxn[(K, V, V), Boolean] = {
    repr.upd[(K, V, V), Boolean] { (m, kvv) =>
      val e = m.entry(kvv._1)
      if (e.isSome()) {
        val v = e.get().getValue()
        if (equ(v, kvv._2)) {
          (m.assoc(kvv._1, kvv._3), true)
        } else {
          (m, false)
        }
      } else {
        (m, false)
      }
    }
  }

  override val get: Rxn[K, Option[V]] = {
    repr.upd[K, Option[V]] { (m, k) =>
      (m, valueOptionFromEntry(m.entry(k)))
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
      val e = m.entry(kv._1)
      if (e.isSome()) {
        val v = e.get().getValue()
        if (equ(v, kv._2)) {
          (m.without(kv._1), true)
        } else {
          (m, false)
        }
      } else {
        (m, false)
      }
    }
  }

  override val clear: Axn[Unit] =
    repr.update(_ => SimpleMap.emptyPhm[K, V])

  final override def values(implicit V: Order[V]): Axn[Vector[V]] = {
    repr.get.map { phm =>
      val b = scala.collection.mutable.ArrayBuffer.newBuilder[V]
      b.sizeHint(phm.size())
      val it = phm.valIterator()
      while (it.hasNext()) {
        b += it.next()
      }
      b.result().sortInPlace()(V.toOrdering).toVector
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike[V] {

    final def get: Axn[V] =
      self.get.provide(key).map(_.getOrElse(default))

    final def upd[B, C](f: (V, B) => (V, C)): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modify { phm =>
          val currVal = phm.getOrElse(key, default)
          val (newVal, c) = f(currVal, b)
          if (equ(newVal, default)) (phm.without(key), c)
          else (phm.assoc(key, newVal), c)
        }
      }
    }

    final def updWith[B, C](f: (V, B) => Axn[(V, C)]): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modifyWith { phm =>
          val currVal = phm.getOrElse(key, default)
          f(currVal, b).map {
            case (newVal, c) =>
              if (equ(newVal, default)) (phm.without(key), c)
              else (phm.assoc(key, newVal), c)
          }
        }
      }
    }
  }

  private[data] final def unsafeSnapshot: Axn[ScalaMap[K, V]] = {
    repr.get.map { phm =>
      // NB: ScalaMap won't use a custom
      // Hash; this is one reason why
      // this method is `unsafe`.
      val b = ScalaMap.newBuilder[K, V]
      phm.iterator().forEachRemaining { entry =>
        b += ((entry.getKey(), entry.getValue()))
      }
      b.result()
    }
  }
}

private object SimpleMap {

  final def apply[K: Hash, V]: Axn[Map.Extra[K, V]] = {
    Ref.unpadded[PersistentHashMap[K, V]](emptyPhm[K, V]).map {
      new SimpleMap(_)
    }
  }

  private def emptyPhm[K: Hash, V]: PersistentHashMap[K, V] =
    PersistentHashMap.empty[K, V](equatorFromHash[K])

  private[this] def equatorFromHash[A](implicit A: Hash[A]): Equator[A] = {
    new Equator[A] {
      final override def eq(x: A, y: A): Boolean =
        A.eqv(x, y)
      final override def hash(x: A): Int =
        A.hash(x)
    }
  }
}
