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

import scala.collection.concurrent.{ TrieMap, Map => CMap }
import scala.collection.immutable.{ Map => ScalaMap }
import scala.util.hashing.byteswap32

import cats.kernel.{ Hash, Order }
import cats.syntax.all._

import internal.skiplist.SkipListMap
import internal.mcas.Mcas
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
 * since we get it for free from the stdlib. (Also, this is
 * written against the `scala.collection.concurrent.Map`
 * interface, so it can work with other concurrent maps too.)
 *
 * Unlike `ttrie` in the paper (section 3.3), we don't leak
 * memory on failed lookups, and removal compacts the trie.
 *
 * The basic idea of `ttrie` seems essentially the same as
 * the earlier idea of "transactional predication" in
 * "Composable Operations on High-Performance Concurrent
 * Collections" by Nathan G. Bronson
 * (https://web.archive.org/web/20221206161946/https://stacks.stanford.edu/file/druid:gm457gs5369/nbronson_thesis_final-augmented.pdf).
 * However, transactional predication has a more sophisticated
 * cleanup scheme than we have here (section 3.4.1–3).
 *
 * TODO: see if we could use more ideas from transactional predication.
 */
private final class Ttrie[K, V] private (
  m: CMap[K, Ref[V]],
  str: Ref.AllocationStrategy,
) extends Map.UnsealedMap[K, V] { self =>

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
   * - The result ref is already part of the current log.
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
   * (see below). An undetected conflict could arise, if, e.g.,
   * Rxn1 reads a ref, Rxn2 tombs and changes it, then Rxn1
   * reads it again with `getRef`, but now it will be a
   * *different* ref.
   */
  private[this] final def getRef: K =#> Ref[V] = {
    Rxn.computed(getRefWithKey)
  }

  private[this] final def getRefWithKey(k: K): Axn[Ref[V]] = {
    Axn.unsafe.suspendContext { ctx =>
      val newRef = Ref.unsafe[V](Init[V], str, ctx.refIdGen)
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
        val currVal = ticket.unsafePeek
        if (isEnd[V](currVal) && ticket.unsafeIsReadOnly) {
          // ticket `nv eq End` AND `nv eq ov`
          // => ticket `ov` is also `End`
          // => the `End` was read directly from the ref
          // => it will never change, so ref can be removed,
          //    and we'll retry
          // (NB: in this case, `ref` won't be inserted into the log)
          Rxn.unsafe.delayContext(unsafeDelRef(k, ref, _)) >>> getRefWithKey(k)
        } else {
          // Make sure `ref` is in the log, then force re-validation:
          ticket.unsafeValidate >>> Rxn.unsafe.forceValidate.as(ref)
          // Re-validation is necessary, because otherwise
          // there would be no conflict detected between
          // a new ref and the previous (tombed and removed)
          // one.
          // TODO: What's the performance hit of revalidation?
          // TODO: Could we do a *partial* revalidation?
          // TODO: Transactional predication (see above) has
          // TODO: a solution to this problem, and is able to
          // TODO: avoid revalidation. See `embalm` and `resurrect`
          // TODO: in section 3.4.1.
        }
      }
    }
  }

  /** Only call if `ref` really contains `End`! */
  private[this] final def unsafeDelRef(k: K, ref: Ref[V], ctx: Mcas.ThreadContext): Unit = {
    // just to be sure:
    _assert(isEnd(ctx.readDirect(ref.loc)))
    // NB: `TrieMap#remove(K, V)` checks V with
    // universal equality; fortunately, for a
    // ref, reference and univ. eq. are the same.
    // (We'd actually like `removeRefEq`, but that
    // one is private.)
    m.remove(k, ref) : Unit
  }

  private[this] final def cleanupLater(key: K, ref: Ref[V]): Axn[Unit] = {
    // First we need to check, if `End` was actually
    // committed (into `ref`), since the Rxn
    // which added us as a post-commit action
    // might've chaged it back later (in its log):
    ref.unsafeDirectRead.flatMapF { v =>
      if (isEnd[V](v)) { // OK, we can delete it:
        Rxn.unsafe.delayContext(unsafeDelRef(key, ref, _))
      } else { // oops, don't delete it:
        Rxn.unit
      }
    }
  }

  private[this] final def cleanupLaterIfNone[A](key: K, ref: Ref[V]): Rxn[Option[A], Unit] = {
    Rxn.computed { option =>
      if (option.isEmpty) cleanupLater(key, ref)
      else Rxn.unit
    }
  }

  private[this] final def cleanupLaterIfNeeded[A](key: K, ref: Ref[V]): Rxn[ReplaceResult, Unit] = {
    Rxn.computed { rr =>
      if (rr.needsCleanup) cleanupLater(key, ref)
      else Rxn.unit
    }
  }

  final def get: K =#> Option[V] = {
    Rxn.computed { (key: K) =>
      getRefWithKey(key).flatMapF { ref =>
        ref.modify { v =>
          if (isInit(v) || isEnd(v)) {
            // it is possible, that we created
            // the ref with `Init`, so we must
            // write `End`, to not leak memory:
            (End[V], None)
          } else {
            (v, Some(v))
          }
        }.postCommit(cleanupLaterIfNone(key, ref))
      }
    }
  }

  final def put: (K, V) =#> Option[V] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.modify { ov =>
          if (isInit(ov) || isEnd(ov)) {
            (v, None)
          } else {
            (v, Some(ov))
          }
        }
    }
  }

  final def putIfAbsent: (K, V) =#> Option[V] = {
    getRef.first[V].flatMapF {
      case (ref, v) =>
        ref.modify { ov =>
          if (isInit(ov) || isEnd(ov)) {
            (v, None)
          } else {
            (ov, Some(ov))
          }
        }
    }
  }

  final def replace: Rxn[(K, V, V), Boolean] = {
    Rxn.computed { (kvv: (K, V, V)) =>
      getRefWithKey(kvv._1).flatMapF { ref =>
        ref.modify[ReplaceResult] { ov =>
          if (isInit(ov) || isEnd(ov)) {
            (End[V], FalseCleanup)
          } else {
            if (equ(ov, kvv._2)) (kvv._3, TrueNoCleanup)
            else (ov, FalseNoCleanup)
          }
        }.postCommit(cleanupLaterIfNeeded(kvv._1, ref)).map { rr =>
          rr.toBoolean
        }
      }
    }
  }

  final def del: Rxn[K, Boolean] = {
    Rxn.computed { (key: K) =>
      getRefWithKey(key).flatMapF { ref =>
        ref.modify { ov =>
          if (isInit(ov) || isEnd(ov)) {
            (End[V], false)
          } else {
            (End[V], true)
          }
        }.postCommit(cleanupLater(key, ref))
      }
    }
  }

  final def remove: Rxn[(K, V), Boolean] = {
    Rxn.computed { (kv: (K, V)) =>
      getRefWithKey(kv._1).flatMapF { ref =>
        ref.modify { ov =>
          if (isInit(ov) || isEnd(ov)) {
            (End[V], false)
          } else {
            if (equ(ov, kv._2)) (End[V], true)
            else (ov, false)
          }
        }.postCommit(cleanupLater(kv._1, ref))
      }
    }
  }

  final override def refLike(key: K, default: V): RefLike[V] = new RefLike[V] {

    final def get: Axn[V] =
      self.get.provide(key).map(_.getOrElse(default))

    // TODO: maybe override `upd` and/or `getAndSet` if we can make it faster than the default impl.

    final def updWith[B, C](f: (V, B) => Axn[(V, C)]): B =#> C = {
      getRefWithKey(key).flatMap { ref =>
        ref.updWith[B, C] { (oldVal, b) =>
          val currVal = if (isInit(oldVal) || isEnd(oldVal)) {
            default
          } else {
            oldVal
          }
          f(currVal, b).flatMapF {
            case (newVal, c) =>
              if (equ(newVal, default)) {
                // it is possible, that we created
                // the ref with `Init`, so we must
                // write `End`, to not leak memory:
                Rxn.postCommit(cleanupLater(key, ref)).as((End[V], c))
              } else {
                Rxn.pure((newVal, c))
              }
          }
        }
      }
    }
  }

  private[data] final def unsafeSnapshot: Axn[ScalaMap[K, V]] = {
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
          val v = kv._2
          if (isInit(v) || isEnd(v)) {
            ()
          } else {
            b += (kv._1 -> v)
          }
        }
        b.result()
      }
    }
  }

  private[data] final def unsafeTrieMapSize: Axn[Int] = {
    // NB: non-composable, sees empty refs, etc.
    Axn.unsafe.delay { m.size }
  }
}

private object Ttrie {

  /*
   * The value of a ref in the trie can
   * go through these states (it starts
   * from `Init`):
   *
   *         ---> (v1: V) ---
   *        /        ↕       \
   *   Init ----> (v2: V) ----+--> End
   *        \        ↕       /
   *         ---> (v3: V) ---
   *                 ↕
   *                ...
   *
   * After `End` is committed, it can
   * never change. (A tentative `End` in
   * the write-log can still change though.)
   */

  private[this] final case object _Init

  @inline private final def Init[V]: V =
    _Init.asInstanceOf[V]

  @inline private final def isInit[V](v: V): Boolean =
    equ[V](v, Init[V])

  private[this] final case object _End

  @inline private final def End[V]: V =
    _End.asInstanceOf[V]

  @inline private final def isEnd[V](v: V): Boolean =
    equ[V](v, End[V])

  private sealed abstract class ReplaceResult {
    def toBoolean: Boolean
    def needsCleanup: Boolean
  }

  private final object TrueNoCleanup extends ReplaceResult {
    final override def toBoolean = true
    final override def needsCleanup = false
  }

  private final object FalseNoCleanup extends ReplaceResult {
    final override def toBoolean = false
    final override def needsCleanup = false
  }

  private final object FalseCleanup extends ReplaceResult {
    final override def toBoolean = false
    final override def needsCleanup = true
  }

  def apply[K, V](str: Ref.AllocationStrategy)(implicit K: Hash[K]): Axn[Ttrie[K, V]] = {
    Axn.unsafe.delay {
      val m = new TrieMap[K, Ref[V]](
        hashf = { k => byteswap32(K.hash(k)) },
        ef = K.eqv(_, _),
      )
      new Ttrie[K, V](m, str)
    }
  }

  def skipListBased[K, V](str: Ref.AllocationStrategy)(implicit K: Order[K]): Axn[Ttrie[K, V]] = {
    Axn.unsafe.delay {
      val m = new SkipListMap[K, Ref[V]]()(K)
      new Ttrie[K, V](m, str)
    }
  }
}
