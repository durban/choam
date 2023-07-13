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

import scala.collection.immutable.{ Map => SMap }

import cats.kernel.{ Hash, Order }

import SimpleMap.Wrapper

private final class SimpleMap[K, V] private (
  repr: Ref[SMap[Wrapper[K], V]],
)(implicit K: Hash[K]) extends Map.UnsealedMapExtra[K, V] { self =>

  override def put: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      val kw = Wrapper(kv._1)
      (m + (kw -> kv._2), m.get(kw))
    }
  }

  override def putIfAbsent: Rxn[(K, V), Option[V]] = {
    repr.upd[(K, V), Option[V]] { (m, kv) =>
      val kw = Wrapper(kv._1)
      m.get(kw) match {
        case None =>
          (m + (kw -> kv._2), None)
        case Some(v) =>
          (m, Some(v))
      }
    }
  }

  override def replace: Rxn[(K, V, V), Boolean] = {
    repr.upd[(K, V, V), Boolean] { (m, kvv) =>
      val kw = Wrapper(kvv._1)
      m.get(kw) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, kvv._2) =>
          (m + (kw -> kvv._3), true)
        case _ =>
          (m, false)
      }
    }
  }

  override def get: Rxn[K, Option[V]] = {
    repr.upd[K, Option[V]] { (m, k) =>
      (m, m.get(Wrapper(k)))
    }
  }

  override def del: Rxn[K, Boolean] = {
    repr.upd[K, Boolean] { (m, k) =>
      val newM = m - Wrapper(k)
      (newM, newM.size != m.size)
    }
  }

  override def remove: Rxn[(K, V), Boolean] = {
    repr.upd[(K, V), Boolean] { (m, kv) =>
      val kw = Wrapper(kv._1)
      m.get(kw) match {
        case None =>
          (m, false)
        case Some(v) if equ(v, kv._2) =>
          (m - kw, true)
        case _ =>
          (m, false)
      }
    }
  }

  override def clear: Axn[Unit] =
    repr.update(_ => SMap.empty)

  override def values(implicit V: Order[V]): Axn[Vector[V]] = {
    repr.get.map { m =>
      m.valuesIterator.toVector.sorted(V.toOrdering)
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike[V] {

    val wk = Wrapper(key)

    final def get: Axn[V] =
      self.get.provide(key).map(_.getOrElse(default))

    final def upd[B, C](f: (V, B) => (V, C)): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modify { phm =>
          val currVal = phm.getOrElse(wk, default)
          val (newVal, c) = f(currVal, b)
          if (equ(newVal, default)) (phm - wk, c)
          else (phm.updated(wk, newVal), c)
        }
      }
    }

    final def updWith[B, C](f: (V, B) => Axn[(V, C)]): B =#> C = {
      Rxn.computed[B, C] { (b: B) =>
        repr.modifyWith { phm =>
          val currVal = phm.getOrElse(wk, default)
          f(currVal, b).map {
            case (newVal, c) =>
              if (equ(newVal, default)) (phm - wk, c)
              else (phm.updated(wk, newVal), c)
          }
        }
      }
    }
  }

  private[data] final def unsafeSnapshot: Axn[SMap[K, V]] = {
    repr.get.map { m =>
      // NB: SMap won't use a custom
      // Hash; this is one reason why
      // this method is `unsafe`.
      val b = SMap.newBuilder[K, V]
      m.iterator.foreach { wkv =>
        b += ((wkv._1.k, wkv._2))
      }
      b.result()
    }
  }
}

private object SimpleMap {

  def apply[K, V](implicit K: Hash[K]): Axn[Map.Extra[K, V]] = {
    Ref[SMap[Wrapper[K], V]](SMap.empty).map { repr =>
      new SimpleMap[K, V](repr)
    }
  }

  // Yeah, we're unfortunately wrapping each key...
  private final class Wrapper[K](val k: K)(implicit K: Hash[K]) {
    final override def hashCode: Int =
      K.hash(k)
    final override def equals(that: Any): Boolean = that match {
      case that: Wrapper[_] =>
        // TODO: probably safe, as `Map` is invariant in `K`,
        // TODO: and users have no access to `Wrapper`
        K.eqv(k, that.k.asInstanceOf[K])
      case _ =>
        false
    }
  }

  private final object Wrapper {
    def apply[K : Hash](k: K): Wrapper[K] =
      new Wrapper(k)
  }
}
