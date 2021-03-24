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

import kcas.Ref

// TODO: use Eq and Hash
trait Map[K, V] {
  def put: React[(K, V), Option[V]]
  def putIfAbsent: React[(K, V), Option[V]]
  def replace: React[(K, V, V), Boolean]
  def get: React[K, Option[V]]
  def del: React[K, Boolean]
  def remove: React[(K, V), Boolean]
  def snapshot: React[Unit, immutable.Map[K, V]]
  def clear: React[Unit, immutable.Map[K, V]]
}

final object Map {

  def naive[K, V]: React[Unit, Map[K, V]] = React.delay { _ =>
    new Map[K, V] {

      private[this] val repr: Ref[immutable.Map[K, V]] =
        Ref.mk(immutable.Map.empty)

      override val put = React.computed { (kv: (K, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          old <- repr.unsafeCas(m, m + kv) >>> (m.get(kv._1) match {
            case s @ Some(_) =>
               React.ret(s)
            case None =>
              React.ret(None)
          })
        } yield old
      }

      override val putIfAbsent = React.computed { (kv: (K, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          old <- m.get(kv._1) match {
            case s @ Some(_) =>
              repr.unsafeCas(m, m) >>> React.ret(s)
            case None =>
              repr.unsafeCas(m, m + kv) >>> React.ret(None)
          }
        } yield old
      }

      override val replace = React.computed { (kvv: (K, V, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          ok <- m.get(kvv._1) match {
            case Some(v) =>
              if (equ(v, kvv._2)) {
                repr.unsafeCas(m, m + (kvv._1 -> kvv._3)) >>> React.ret(true)
              } else {
                repr.unsafeCas(m, m) >>> React.ret(false)
              }
            case None =>
              repr.unsafeCas(m, m) >>> React.ret(false)
          }
        } yield ok
      }

      override val get = React.computed { (k: K) =>
        repr.getter.map(_.get(k))
      }

      override val del = React.computed { (k: K) =>
        for {
          m <- repr.unsafeInvisibleRead
          ok <- m.get(k) match {
            case Some(_) =>
              repr.unsafeCas(m, m - k) >>> React.ret(true)
            case None =>
              repr.unsafeCas(m, m) >>> React.ret(false)
          }
        } yield ok
      }

      override val remove = React.computed { (kv: (K, V)) =>
        for {
          m <- repr.unsafeInvisibleRead
          ok <- m.get(kv._1) match {
            case Some(w) =>
              if (equ(w, kv._2)) {
                repr.unsafeCas(m, m - kv._1) >>> React.ret(true)
              } else {
                repr.unsafeCas(m, m) >>> React.ret(false)
              }
            case None =>
              repr.unsafeCas(m, m) >>> React.ret(false)
          }
        } yield ok
      }

      override val snapshot =
        repr.getter

      override val clear =
        repr.modify(_ => immutable.Map.empty)
    }
  }
}
