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
  def snapshot: Rxn[Unit, immutable.Map[K, V]]
  def clear: Rxn[Unit, immutable.Map[K, V]]
}

object Map {

  def naive[K, V]: Rxn[Unit, Map[K, V]] = Rxn.delay { _ =>
    new Map[K, V] {

      private[this] val repr: Ref[immutable.Map[K, V]] =
        Ref.unsafe(immutable.Map.empty)

      override val put = Rxn.computed { (kv: (K, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          old <- repr.unsafeCas(m, m + kv) >>> (m.get(kv._1) match {
            case s @ Some(_) =>
               Rxn.ret(s)
            case None =>
              Rxn.ret(None)
          })
        } yield old
      }

      override val putIfAbsent = Rxn.computed { (kv: (K, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          old <- m.get(kv._1) match {
            case s @ Some(_) =>
              repr.unsafeCas(m, m) >>> Rxn.ret(s)
            case None =>
              repr.unsafeCas(m, m + kv) >>> Rxn.ret(None)
          }
        } yield old
      }

      override val replace = Rxn.computed { (kvv: (K, V, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          ok <- m.get(kvv._1) match {
            case Some(v) =>
              if (equ(v, kvv._2)) {
                repr.unsafeCas(m, m + (kvv._1 -> kvv._3)) >>> Rxn.ret(true)
              } else {
                repr.unsafeCas(m, m) >>> Rxn.ret(false)
              }
            case None =>
              repr.unsafeCas(m, m) >>> Rxn.ret(false)
          }
        } yield ok
      }

      override val get = Rxn.computed { (k: K) =>
        repr.get.map(_.get(k))
      }

      override val del = Rxn.computed { (k: K) =>
        for {
          m <- repr.unsafeInvisibleRead
          ok <- m.get(k) match {
            case Some(_) =>
              repr.unsafeCas(m, m - k) >>> Rxn.ret(true)
            case None =>
              repr.unsafeCas(m, m) >>> Rxn.ret(false)
          }
        } yield ok
      }

      override val remove = Rxn.computed { (kv: (K, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          ok <- m.get(kv._1) match {
            case Some(w) =>
              if (equ(w, kv._2)) {
                repr.unsafeCas(m, m - kv._1) >>> Rxn.ret(true)
              } else {
                repr.unsafeCas(m, m) >>> Rxn.ret(false)
              }
            case None =>
              repr.unsafeCas(m, m) >>> Rxn.ret(false)
          }
        } yield ok
      }

      override val snapshot =
        repr.get

      override val clear =
        repr.getAndUpdate(_ => immutable.Map.empty)
    }
  }
}
