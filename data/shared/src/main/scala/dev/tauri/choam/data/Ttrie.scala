/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.collection.concurrent.TrieMap

import cats.kernel.Hash

// TODO: Even failed lookups create a new
// TODO: ref, and put it into the tree.
// TODO: This can use a lot of memory,
// TODO: and possibly can be used to do
// TODO: some kind of DoS attack (section 3.3).
// TODO: Even `remove` and `del` can create
// TODO: new refs in the tree!

// TODO: There is no "real" remove operation;
// TODO: any removal currently leaves a tombstone
// TODO: in the tree (a ref which contains `None`).
// TODO: This means memory usage can grow
// TODO: indefinitely (also in section 3.3).

/**
 * Based on `ttrie` in "Durability and Contention in Software
 * Transactional Memory" by Michael Schröder, which is itself
 * based on the concurrent trie of Prokopec, Bagwell, and Odersky
 * (`scala.collection.concurrent.TrieMap`).
 *
 * We're using a `TrieMap` directly (instead of reimplementing),
 * since we get it for free from the stdlib.
 */
private final class Ttrie[K, V](
  m: TrieMap[K, Ref[Option[V]]],
) extends Map[K, V] {

  /**
   * Based on `getTVar` in the paper
   *
   * Invariants:
   *
   * 1. `getRef(k1)` ≡ `getRef(k2)`  <=>  `k1` ≡ `k2`
   *
   * 2. `getRef` itself doesn't read/write any `Ref`s.
   */
  private[this] final def getRef: K =#> Ref[Option[V]] = {
    Rxn.unsafe.delay { (k: K) =>
      val newRef = Ref.unsafe[Option[V]](None)
      m.putIfAbsent(k, newRef) match {
        case Some(existingRef) =>
          existingRef
        case None =>
          newRef
      }
    }
  }

  final def put: (K, V) =#> Option[V] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.getAndSet.provide(Some(v))
    }
  }

  final def putIfAbsent: (K, V) =#> Option[V] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.modify {
          case None => (Some(v), None)
          case s @ Some(_) => (s, s)
        }
    }
  }

  final def replace: Rxn[(K, V, V), Boolean] = {
    getRef.first[(V, V)].flatMapF {
      case (ref, (expVal, newVal)) =>
        ref.modify {
          case None =>
            (None, false)
          case s @ Some(currVal) =>
            if (equ(currVal, expVal)) (Some(newVal), true)
            else (s, false)
        }
    }.contramap(kvv => (kvv._1, (kvv._2, kvv._3)))
  }

  final def get: K =#> Option[V] =
    getRef.flatMapF(_.get)

  final def del: Rxn[K, Boolean] =
    getRef.flatMapF(_.modify(ov => (None, ov.isDefined)))

  final def remove: Rxn[(K, V), Boolean] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.modify {
          case None => (None, false)
          case s @ Some(currVal) =>
            if (equ(currVal, v)) (None, true)
            else (s, false)
        }
    }
  }

  final def values: Axn[Vector[V]] = {
    Axn.unsafe.delay {
      // TODO: Is this safe? TrieMap makes
      // TODO: a snapshot for the iterator,
      // TODO: so it is atomic; but what happens
      // TODO: if before commit, a new ref is
      // TODO: added?
      m.valuesIterator.toList
    }.flatMapF { (lst: List[Ref[Option[V]]]) =>
      Rxn.consistentReadMany(lst).map { (lst: List[Option[V]]) =>
        lst.collect { case Some(v) => v }.toVector
      }
    }
  }
}

private final object Ttrie {

  def apply[K, V](implicit K: Hash[K]): Axn[Ttrie[K, V]] = {
    Axn.unsafe.delay {
      val m = new TrieMap[K, Ref[Option[V]]](
        hashf = K.hash(_),
        ef = K.eqv(_, _),
      )
      new Ttrie[K, V](m)
    }
  }
}
