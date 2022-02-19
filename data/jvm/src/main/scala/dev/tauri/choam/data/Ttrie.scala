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
import scala.collection.immutable.{ Map => ScalaMap }

import cats.kernel.Hash
import cats.syntax.all._

import mcas.MCAS
import Ttrie._

/**
 * Based on `ttrie` in "Durability and Contention in Software
 * Transactional Memory" by Michael Schröder
 * (https://web.archive.org/web/20211203183825/https://mcschroeder.github.io/files/stmio_thesis.pdf),
 * which is itself based on the concurrent trie of
 * Prokopec, et al. (`scala.collection.concurrent.TrieMap` and
 * https://web.archive.org/web/20210506144154/https://lampwww.epfl.ch/~prokopec/ctries-snapshot.pdf).
 *
 * We're using a `TrieMap` directly (instead of reimplementing),
 * since we get it for free from the stdlib.
 */
private final class Ttrie[K, V] private (
  m: TrieMap[K, TrieRef[V]],
) extends Map[K, V] { self =>

  /*
   * We store refs in a `TrieMap`; the management
   * of this `TrieMap` is *outside* of the `Rxn` log.
   * (It is performed by `unsafe.delay` and similar.)
   * This means, that conflicts are resolved by the
   * `TrieMap` itself, and `Rxn` conflicts only arise
   * if there are `Rxn`s actually working with the
   * same refs (in which case they're unavoidable).
   * But it also means, that we have to be *extremely*
   * careful with modifying the `TrieMap`, since these
   * modifications are (1) immediately visible to other
   * running `Rxn`s, and (2) will not be rolled back
   * on retries.
   */

  // TODO: See if these could be useful:
  // TODO: http://aleksandar-prokopec.com/resources/docs/p137-prokopec.pdf
  // TODO: http://aleksandar-prokopec.com/resources/docs/cachetrie-remove.pdf

  /**
   * Based on `getTVar` in the paper, but
   * different because of deletion.
   *
   * The 2 invariants are also different
   * from the ones in the paper:
   * 1. For all k: K, r1 := getRef(k) and r2 := getRef(k);
   *    either r1 ≡ r2, or at least one of r1 and r2 have
   *    been already tombed (i.e., contains `End`).
   * 2. The only ref read by `getRef` is the result ref,
   *    and `getRef` doesn't write to any refs.
   *
   * Also guarantees the following:
   * - The result `ref` is a ref associated with the key.
   * - The result is already part of the current log.
   * - If the result is in the write-log (i.e., modified),
   *   then the current value (nv) may be `End`.
   * - Otherwise, the current value (in the read-log) is
   *   guaranteed not to be `End`.
   * - (The previous 2 points mean, that if after `getRef`
   *   the Rxn reads `End`, it can still safely replace it,
   *   because it is not yet committed.)
   *
   * Since we're switching out tombed refs, we lose automatic
   * conflict detection on reads. So we need to do it manually
   * (see below). An undetected conflict can arise, if, e.g.,
   * Rxn1 reads a ref, Rxn2 tombs and changes it, then Rxn1
   * reads it again with `getRef`, but now it will be a
   * *different* ref.
   */
  private[this] final def getRef: K =#> TrieRef[V] = {
    Rxn.computed(getRefWithKey)
  }

  private[this] final def getRefWithKey(k: K): Axn[TrieRef[V]] = {
    Axn.unsafe.suspend {
      val newRef = Ref.unsafe[State[V]](Init)
      val ref = m.putIfAbsent(k, newRef) match {
        case Some(existingRef) =>
          existingRef
        case None =>
          newRef
      }
      // Note: we need to read here even if
      // we created the ref, because it's
      // already visible to others.
      ref.unsafeTicketRead.flatMapF { ticket =>
        ticket.unsafePeek match {
          case End if ticket.unsafeIsReadOnly =>
            // ticket `nv eq End` AND `nv eq ov`
            // => ticket `ov` is also `End`
            // => the `End` was read directly from the ref
            // => it will never change, so ref can be removed:
            // (NB: in this case, `ref` won't be inserted into the log)
            Rxn.unsafe.context(unsafeDelRef(k, ref, _)) *> getRefWithKey(k)
          case _ =>
            // Make sure `ref` is in the log, then force re-validation:
            ticket.unsafeValidate >>> Rxn.unsafe.forceValidate.as(ref)
            // Re-validation is necessary, because otherwise
            // there would be no conflict detected between
            // a new ref and the previous (tombed and removed)
            // one.
            // TODO: What's the performance hit of revalidation?
            // TODO: Could we do a *partial* revalidation?
        }
      }
    }
  }

  /** Only call if `ref` really contains `End`! */
  private[this] final def unsafeDelRef(k: K, ref: TrieRef[V], ctx: MCAS.ThreadContext): Unit = {
    // just to be sure:
    assert(ctx.readDirect(ref.loc) eq End)
    // NB: `TrieMap#remove(K, V)` checks V with
    // universal equality; fortunately, for a
    // ref, reference and univ. eq. are the same.
    // (We'd actually like `removeRefEq`, but that
    // one is private.)
    m.remove(k, ref)
    ()
  }

  private[this] final def cleanupLater(key: K, ref: TrieRef[V]): Axn[Unit] = {
    // First we need to check, if `End` was actually
    // committed (into `ref`), since the Rxn
    // which added us as a post-commit action
    // might've chaged it back later (in its log):
    ref.unsafeDirectRead.flatMapF {
      case End => // OK, we can delete it:
        Rxn.unsafe.context(unsafeDelRef(key, ref, _))
      case _ => // oops, don't delete it:
        Rxn.unit
    }
  }

  private[this] final def cleanupLaterIfNone[A](key: K, ref: TrieRef[V]): Rxn[Option[A], Unit] = {
    Rxn.computed { option =>
      if (option.isEmpty) cleanupLater(key, ref)
      else Rxn.unit
    }
  }

  private[this] final def cleanupLaterIfOdd[A](key: K, ref: TrieRef[V]): Rxn[Int, Unit] = {
    Rxn.computed { n =>
      if ((n & 1) == 1) cleanupLater(key, ref)
      else Rxn.unit
    }
  }

  final def get: K =#> Option[V] = {
    Rxn.computed { (key: K) =>
      getRefWithKey(key).flatMapF { ref =>
        ref.modify {
          case Init | End =>
            // it is possible, that we created
            // the ref with `Init`, so we have
            // to write `End`, to not leak memory:
            (End, None)
          case v @ Value(w) =>
            (v, Some(w))
        }.postCommit(cleanupLaterIfNone(key, ref))
      }
    }
  }

  final def put: (K, V) =#> Option[V] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.modify {
          case Init | End => (Value(v), None)
          case Value(oldVal) => (Value(v), Some(oldVal))
        }
    }
  }

  final def putIfAbsent: (K, V) =#> Option[V] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.modify {
          case Init | End => (Value(v), None)
          case v @ Value(w) => (v, Some(w))
        }
    }
  }

  final def replace: Rxn[(K, V, V), Boolean] = {
    Rxn.computed { (kvv: (K, V, V)) =>
      getRefWithKey(kvv._1).flatMapF { ref =>
        ref.modify {
          case Init | End =>
            (End, 1) // 0b01 -> false, cleanup
          case ov @ Value(currVal) =>
            if (equ(currVal, kvv._2)) (Value(kvv._3), 2) // 0b10 -> true, no cleanup
            else (ov, 0) // 0b00 -> false, no cleanup
        }.postCommit(cleanupLaterIfOdd(kvv._1, ref)).map { n =>
          (n & 2) == 2
        }
      }
    }
  }

  final def del: Rxn[K, Boolean] = {
    Rxn.computed { (key: K) =>
      getRefWithKey(key).flatMapF { ref =>
        ref.modify {
          case Init | End => (End, false)
          case Value(_) => (End, true)
        }.postCommit(cleanupLater(key, ref))
      }
    }
  }

  final def remove: Rxn[(K, V), Boolean] = {
    Rxn.computed { (kv: (K, V)) =>
      getRefWithKey(kv._1).flatMapF { ref =>
        ref.modify {
          case Init | End =>
            (End, false)
          case value @ Value(currVal) =>
            if (equ(currVal, kv._2)) (End, true)
            else (value, false)
        }.postCommit(cleanupLater(kv._1, ref))
      }
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike[V] {

    final def get: Axn[V] =
      self.get.provide(key).map(_.getOrElse(default))

    final def upd[B, C](f: (V, B) => (V, C)): B =#> C =
      this.updWith { (v, b) => Rxn.pure(f(v, b)) }

    final def updWith[B, C](f: (V, B) => Axn[(V, C)]): B =#> C = {
      getRef.provide(key).flatMap { ref =>
        ref.updWith[B, C] { (oldVal, b) =>
          val currVal = oldVal match {
            case Init | End => default
            case Value(currVal) => currVal
          }
          f(currVal, b).flatMapF {
            case (newVal, c) =>
              if (equ(newVal, default)) {
                // it is possible, that we created
                // the ref with `Init`, so we have
                // to write `End`, to not leak memory:
                Rxn.postCommit(cleanupLater(key, ref)).as((End, c))
              } else {
                Rxn.pure((Value(newVal), c))
              }
          }
        }
      }
    }
  }

  private[choam] final def unsafeSnapshot: Axn[ScalaMap[K, V]] = {
    // NB: this is not composable,
    // as running it twice in one Rxn
    // may return a different set of
    // refs; this is one reason why
    // this method is `unsafe`.
    Axn.unsafe.delay { m.iterator.toList }.flatMapF { kvs =>
      kvs.traverse { kv =>
        kv._2.get.map { v => (kv._1, v) }
      }.map { kvs =>
        // NB: ScalaMap won't use a custom
        // Hash; this is another reason why
        // this method is `unsafe`.
        val b = ScalaMap.newBuilder[K, V]
        kvs.foreach { kv =>
          kv._2.toOption match {
            case None => ()
            case Some(v) => b += (kv._1 -> v)
          }
        }
        b.result()
      }
    }
  }

  private[choam] final def unsafeTrieMapSize: Axn[Int] = {
    Axn.unsafe.delay { m.size }
  }
}

private final object Ttrie {

  private type TrieRef[V] = Ref[State[V]]

  /**
   * The value of a ref in the trie can
   * go through these states (it starts
   * from `Init`):
   *
   *         ---> Value(...) ---
   *        /        ↕          \
   *   Init ----> Value(...) ----+--> End
   *        \        ↕          /
   *         ---> Value(...) ---
   *                 ↕
   *                ...
   *
   * After `End` is committed, it can
   * never change. (A tentative `End` in
   * the write-log can still change though.)
   */
  private sealed abstract class State[+V] {
    final def toOption: Option[V] = this match {
      case Init | End => None
      case Value(v) => Some(v)
    }
  }

  // TODO: could we optimize these to Init, End, and simply V?
  private final case object Init extends State[Nothing]
  private final case class Value[+V](v: V) extends State[V]
  private final case object End extends State[Nothing]

  // TODO: use improved hashing
  def apply[K, V](implicit K: Hash[K]): Axn[Ttrie[K, V]] = {
    Axn.unsafe.delay {
      val m = new TrieMap[K, TrieRef[V]](
        hashf = K.hash(_),
        ef = K.eqv(_, _),
      )
      new Ttrie[K, V](m)
    }
  }
}
