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
package skiplist

import java.util.concurrent.atomic.{ AtomicReference }
import java.util.concurrent.ThreadLocalRandom

import cats.kernel.Order

// TODO: use `VarHandle`s instead of `AtomicReference`s.
// TODO: try to micro-optimize retrieving the `Order` (see JSR-166).

/**
 * Concurrent skip list map.
 *
 * Note: this implements the `scala.collection.concurrent.Map`
 * interface, but some methods throw an exception, others use
 * reference equality instead of value equality. (We need this
 * to be able to use it without indirection in `Ttrie`.)
 */
private[choam] final class SkipListMap[K, V]()(implicit K: Order[K])
  extends scala.collection.concurrent.Map[K, V] {

  /*
   * This implementation is based on the public
   * domain JSR-166 `ConcurrentSkipListMap`.
   * Contains simplifications, because we just
   * need a few main operations.
   */

  /**
   * Base nodes (which form the base list) store the payload.
   *
   * `next` is the next node in the base list (with a key > than this).
   *
   * A `Node` is a special "marker" node (for deletion) if `key == MARKER`.
   * A `Node` is logically deleted if `value == TOMB`.
   */
  private[this] final class Node private (
    val key: K,
    value: AtomicReference[V],
    n: Node,
  ) extends AtomicReference[Node](n) { next =>

    private[SkipListMap] def this(key: K, value: V, next: Node) = {
      this(key, new AtomicReference(value), next)
    }

    private[SkipListMap] final def isMarker: Boolean = {
      isMARKER(key)
    }

    private[SkipListMap] final def isDeleted(): Boolean = {
      isTOMB(getValue())
    }

    private[SkipListMap] final def getNext(): Node = {
      next.getAcquire()
    }

    private[SkipListMap] final def casNext(ov: Node, nv: Node): Boolean = {
      next.compareAndSet(ov, nv)
    }

    private[SkipListMap] final def getValue(): V = {
      value.getAcquire()
    }

    private[SkipListMap] final def casValue(ov: V, nv: V): Boolean = {
      value.compareAndSet(ov, nv)
    }

    final override def toString: String =
      "Node(...)"
  }

  /** Index nodes */
  private[this] final class Index(
    val node: Node,
    val down: Index,
    r: Index,
  ) extends AtomicReference[Index](r) { right =>

    require(node ne null)

    final def getRight(): Index = {
      right.getAcquire()
    }

    final def setRightPlain(nv: Index): Unit = {
      right.setPlain(nv)
    }

    final def casRight(ov: Index, nv: Index): Boolean = {
      right.compareAndSet(ov, nv)
    }

    final override def toString: String =
      "Index(...)"
  }

  /** The top left index node (or null if empty) */
  private[this] val _head =
    new AtomicReference[Index]

  private[this] val _marker: AnyRef =
    new AnyRef

  private[this] val _tomb: AnyRef =
    new AnyRef

  private[this] final def MARKER: K = {
    _marker.asInstanceOf[K]
  }

  private[this] final def isMARKER(k: K): Boolean = {
    equ(k, MARKER)
  }

  private[this] final def TOMB: V = {
    _tomb.asInstanceOf[V]
  }

  private[this] final def isTOMB(v: V): Boolean = {
    equ(v, TOMB)
  }

  /** @return the old value (if any). */
  final override def put(key: K, value: V): Option[V] = {
    doPut(key, value, onlyIfAbsent = false, tlr = ThreadLocalRandom.current())
  }

  /** @return the existing value (if any). */
  final override def putIfAbsent(key: K, value: V): Option[V] = {
    doPut(key, value, onlyIfAbsent = true, tlr = ThreadLocalRandom.current())
  }

  final override def get(key: K): Option[V] = {
    doGet(key)
  }

  /** @return `true` iff an item have been removed. */
  final def del(key: K): Boolean = {
    doRemove(key, value = TOMB)
  }

  /**
   * Removes the item at `key` if its value
   * (reference) equals `value`.
   *
   * TODO: this overrides a `concurrent.Map`
   * method with incompatible semantics: we
   * use reference equality (instead of value
   * equality).
   *
   * @return `true` iff an item have been removed.
   */
  final override def remove(key: K, value: V): Boolean = {
    doRemove(key, value)
  }

  /**
   * Replaces the item's current value with
   * `nv` iff it (reference) equals `ov`.
   *
   * TODO: this overrides a `concurrent.Map`
   * method with incompatible semantics: we
   * use reference equality (instead of value
   * equality).
   *
   * @return `true` iff a replacement have been made.
   */
  final override def replace(key: K, ov: V, nv: V): Boolean = {
    doReplace(key, ov, nv)
  }

  // `concurrent.Map` methods:

  /**
   * Note: this method is NOT linearizable.
   *
   * Analogous to the iterators in the JSR-166 `ConcurrentSkipListMap`.
   */
  final override def iterator: Iterator[(K, V)] = {
    new Iterator[(K, V)] {

      private[this] var nextNode: Node =
        null

      private[this] var nextValue: V =
        TOMB

      advance(baseHead())

      final override def hasNext: Boolean = {
        this.nextNode ne null
      }

      final override def next(): (K, V) = {
        val n = this.nextNode
        if (n eq null) {
          throw new NoSuchElementException
        } else {
          val k = n.key
          val v = this.nextValue
          advance(n)
          (k, v)
        }
      }

      private[this] final def advance(_b: Node): Unit = {
        var b: Node = _b
        if (b ne null) {
          while (true) {
            val n = b.getNext()
            if (n ne null) {
              val v = n.getValue()
              if (!isTOMB(v)) {
                this.nextValue = v
                this.nextNode = n
                return // scalafix:ok
              } else {
                b = n // and retry
              }
            } else {
              // end of list
              this.nextValue = TOMB
              this.nextNode = null
              return // scalafix:ok
            }
          }
        }
      }
    }
  }

  final override def addOne(elem: (K, V)): this.type = {
    put(elem._1, elem._2)
    this
  }

  final override def subtractOne(elem: K): this.type = {
    del(elem)
    this
  }

  final override def replace(k: K, v: V): Option[V] = {
    throw new NotImplementedError("SkipListMap#replace(K,V)")
  }

  private[choam] final def foreach(cb: (K, V) => Unit): Unit = {
    doForeach(cb)
  }

  final override def toString: String = {
    peekFirstNode() match {
      case null =>
        "SkipListMap()"
      case _ =>
        "SkipListMap(...)"
    }
  }

  /**
   * Compares keys.
   *
   * Analogous to `cpr` in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def cpr(x: K, y: K): Int = {
    K.compare(x, y)
  }

  /**
   * Analogous to `doPut` in the JSR-166 `ConcurrentSkipListMap`.
   */
  @tailrec
  private[this] final def doPut(key: K, value: V, onlyIfAbsent: Boolean, tlr: ThreadLocalRandom): Option[V] = {
    val h = _head.getAcquire()
    var levels = 0 // number of levels descended
    var b: Node = if (h eq null) {
      // head not initialized yet, do it now;
      // first node of the base list is a sentinel
      // (without payload):
      val base = new Node(MARKER, TOMB, null)
      val h = new Index(base, null, null)
      if (_head.compareAndSet(null, h)) base else null
    } else {
      // we have a head; find a node in the base list
      // "close to" (but before) the inserion point:
      var q: Index = h // current position, start from the head
      var foundBase: Node = null // we're looking for this
      while (foundBase eq null) {
        // first try to go right:
        q = walkRight(q, key)
        // then try to go down:
        val d = q.down
        if (d ne null) {
          levels += 1
          q = d // went down 1 level, will continue going right
        } else {
          // reached the base list, break outer loop:
          foundBase = q.node
        }
      }
      foundBase
    }
    if (b ne null) {
      // `b` is a node in the base list, "close to",
      // but before the insertion point
      var z: Node = null // will be the new node when inserted
      var n: Node = null // next node
      var go = true
      while (go) {
        var c = 0 // `cpr` result
        n = b.getNext()
        if (n eq null) {
          // end of the list, insert right here
          c = -1
        } else if (n.isMarker) {
          // someone is deleting `b` right now, will
          // restart insertion (as `z` is still null)
          go = false
        } else {
          val v = n.getValue()
          if (isTOMB(v)) {
            unlinkNode(b, n)
            c = 1 // will retry going right
          } else {
              c = cpr(key, n.key)
              if (c > 0) {
                // continue right
                b = n
              } else if ((c == 0) && (onlyIfAbsent || n.casValue(v, value))) {
                // successfully overwritten existing value (or not, if `onlyIfAbsent`)
                return Some(v) // scalafix:ok
              } // else: c < 0 for sure
          }
        }

        if (c < 0) {
          // found insertion point
          val p = new Node(key, value, n)
          if (b.casNext(n, p)) {
            z = p
            go = false
          } // else: lost a race, retry
        }
      }

      if (z ne null) {
        // we successfully inserted a new node;
        // maybe add extra indices:
        var rnd = tlr.nextLong()
        if ((rnd & 0x3L) == 0L) { // add at least one index with 1/4 probability
          // first create a "tower" of index
          // nodes (all with `.right == null`):
          var skips = levels
          var x: Index = null // most recently created (topmost) index node in the tower
          var go = true
          while (go) {
            // the height of the tower is at most 62
            // we create at most 62 indices in the tower
            // (62 = 64 - 2; the 2 low bits are 0);
            // also, the height is at most the number
            // of levels we descended when inserting
            x = new Index(z, x, null)
            if (rnd >= 0L) {
              // reached the first 0 bit in `rnd`
              go = false
            } else {
              skips -= 1
              if (skips < 0) {
                // reached the existing levels
                go = false
              } else {
                // each additional index level has 1/2 probability
                rnd <<= 1
              }
            }
          }

          // then actually add these index nodes to the skiplist:
          if (addIndices(h, skips, x) && (skips < 0) && (_head.getAcquire() eq h)) {
            // if we successfully added a full height
            // "tower", try to also add a new level
            // (with only 1 index node + the head)
            val hx = new Index(z, x, null)
            val nh = new Index(h.node, h, hx) // new head
            _head.compareAndSet(h, nh)
          }

          if (z.isDeleted()) {
            // was deleted while we added indices,
            // need to clean up:
            findPredecessor(key)
            ()
          }
        } // else: we're done, and won't add indices

        None
      } else { // restart
        doPut(key, value, onlyIfAbsent, tlr)
      }
    } else { // restart
      doPut(key, value, onlyIfAbsent, tlr)
    }
  }

  /**
   * Replaces `oldValue` with `newValue` at `key`.
   *
   * Analogous to `replace(K, V, V)` in the JSR-166 `ConcurrentSkipListMap`.
   *
   * @return `true` iff a replacement have been made.
   */
  private[this] final def doReplace(key: K, oldValue: V, newValue: V): Boolean = {
    while (true) {
      val n = findNode(key)
      if (n eq null) {
        return false // scalafix:ok
      } else {
        val v = n.getValue()
        if (!isTOMB(v)) {
          if (!equ(oldValue, v)) {
            return false // scalafix:ok
          }
          if (n.casValue(v, newValue)) {
            return true // scalafix:ok
          }
          // else: lost race, retry
        } // else: deleted, retry
      }
    }

    false // unreachable
  }

  private[this] final def doGet(key: K): Option[V] = {
    var q = _head.getAcquire()
    if (q ne null) {
      while (true) {
        var inner = true
        // first try to go right on the indices:
        while (inner) {
          val r = q.getRight()
          if (r ne null) {
            val p = r.node
            val v = p.getValue()
            if (p.isMarker || isTOMB(v)) {
              // marker or deleted node, unlink it:
              q.casRight(r, r.getRight())
              // and will retry going right
            } else {
              val c = cpr(key, p.key)
              if (c > 0) {
                q = r // continue going right
              } else if (c == 0) {
                return Some(v) // scalafix:ok
              } else {
                inner = false // will go down
              }
            }
          } else {
            inner = false
          }
        }
        // then go down:
        val d = q.down
        if (d ne null) {
          q = d
        } else {
          // go right in the base list:
          var b = q.node
          while (true) {
            val n = b.getNext()
            if (n ne null) {
              val v = n.getValue()
              if (n.isMarker || isTOMB(v)) {
                // jump over deleted nodes
                b = n // continue right
              } else {
                val c = cpr(key, n.key)
                if (c > 0) {
                  b = n // continue right
                } else if (c == 0) {
                  return Some(v) // scalafix:ok
                } else { // c < 0
                  return None // scalafix:ok
                }
              }
            } else {
              // reached end of list
              return None // scalafix:ok
            }
          }
        }
      }

      null // unreachable
    } else {
      None // empty list
    }
  }

  /**
   * Starting from the `q` index node, walks right while
   * possible by comparing keys (`triggerTime` and `seqNo`).
   * Returns the last index node (at this level) which is
   * still a predecessor of the node with the specified
   * key (`triggerTime` and `seqNo`). This returned index
   * node can be `q` itself. (This method assumes that
   * the specified `q` is a predecessor of the node with
   * the key.)
   *
   * This method has no direct equivalent in the JSR-166
   * `ConcurrentSkipListMap`; the same logic is embedded
   * in various methods as a `while` loop.
   */
  @tailrec
  private[this] final def walkRight(q: Index, key: K): Index = {
    val r = q.getRight()
    if (r ne null) {
      val p = r.node
      if (p.isMarker || p.isDeleted()) {
        // marker or deleted node, unlink it:
        q.casRight(r, r.getRight())
        // and retry:
        walkRight(q, key)
      } else if (cpr(key, p.key) > 0) {
        // we can still go right:
        walkRight(r, key)
      } else {
        // can't go right any more:
        q
      }
    } else {
      // can't go right any more:
      q
    }
  }

  /**
   * Finds the node with the specified key (and optionally
   * value); deletes it logically by CASing the value to
   * `TOMB`; unlinks it (first inserting a marker); removes
   * associated index nodes; and possibly reduces index level.
   *
   * Analogous to `doRemove` in the JSR-166 `ConcurrentSkipListMap`.
   *
   * @param key the key of the node to remove.
   * @param value the value of the node to remove; compared by
   *   object identity; pass `TOMB` to remove a node with any value.
   * @return `true` iff a node was removed.
   */
  private[this] final def doRemove(key: K, value: V): Boolean = {
    var b = findPredecessor(key)
    while (b ne null) { // outer
      var inner = true
      while (inner) {
        val n = b.getNext()
        if (n eq null) {
          return false // scalafix:ok
        } else if (n.isMarker) {
          inner = false
          b = findPredecessor(key)
        } else  {
          val ncb = n.getValue()
          if (isTOMB(ncb)) {
            unlinkNode(b, n)
            // and retry `b.getNext()`
          } else {
            val c = cpr(key, n.key)
            if (c > 0) {
              b = n
            } else if (c < 0) {
              return false // scalafix:ok
            } else if (!isTOMB(value) && !equ(value, ncb)) {
              // `value` was specified, and doesn't match:
              return false // scalafix:ok
            } else if (n.casValue(ncb, TOMB)) {
              // successfully logically deleted
              unlinkNode(b, n)
              findPredecessor(key) // cleanup
              tryReduceLevel()
              return true // scalafix:ok
            }
          }
        }
      }
    }

    false
  }

  /**
   * Calls `cb` with each (non-deleted) key-value
   * pair in the list. Note: this method is not
   * atomic or consistent (see "weakly consistent"
   * iterators in the JDK).
   *
   * Analogous to the iterators in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def doForeach(cb: (K, V) => Unit): Unit = {
    var n = baseHead()
    while (n ne null) {
      val key = n.key
      if (!isMARKER(key)) {
        val value = n.getValue()
        if (!isTOMB(value)) {
          cb(key, value)
        }
      }
      n = n.getNext()
    }
  }

  /** Non-linearizable! */
  final override def size: Int = {
    var count = 0
    var n = baseHead()
    while (n ne null) {
      val key = n.key
      if (!isMARKER(key)) {
        val value = n.getValue()
        if (!isTOMB(value)) {
          count += 1
        }
      }
      n = n.getNext()
    }
    count
  }

  /**
   * Returns the first node of the base list.
   * Skips logically deleted nodes, so the
   * returned node was non-deleted when calling
   * this method (but beware of concurrent deleters).
   */
  private[this] final def peekFirstNode(): Node = {
    var b = baseHead()
    if (b ne null) {
      var n: Node = null
      while ({
        n = b.getNext()
        (n ne null) && (n.isDeleted())
      }) {
        b = n
      }

      n
    } else {
      null
    }
  }

  /**
   * The head of the base list (or `null` if uninitialized).
   *
   * Analogous to `baseHead` in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def baseHead(): Node = {
    val h = _head.getAcquire()
    if (h ne null) h.node else null
  }

  /**
   * Analogous to `findNode` in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def findNode(key: K): Node = {
    while (true) {
      var b = findPredecessor(key)
      if (b ne null) {
        var inner = true
        while (inner) {
          val n = b.getNext()
          if (n eq null) {
            return null // scalafix:ok
          } else {
            val k = n.key
            if (isMARKER(k)) {
              // b is deleted, retry `findPredecessor`:
              inner = false
            } else {
              val v = n.getValue()
              if (isTOMB(v)) {
                // n is deleted, unlink and retry:
                unlinkNode(b, n)
              } else {
                val c = cpr(key, k)
                if (c > 0) {
                  b = n // continue right
                } else if (c == 0) {
                  return n // scalafix:ok
                } else {
                  return null // scalafix:ok
                }
              }
            }
          }
        }
      } else {
        return null // scalafix:ok
      }
    }

    null // unreachable
  }

  /**
   * Adds indices after an insertion was performed (e.g. `doPut`).
   * Descends iteratively to the highest index to insert, and
   * from then recursively calls itself to insert lower level
   * indices. Returns `false` on staleness, which disables higher
   * level insertions (from the recursive calls).
   *
   * Analogous to `addIndices` in the JSR-166 `ConcurrentSkipListMap`.
   *
   * @param _q starting index node for the current level
   * @param _skips levels to skip down before inserting
   * @param x the top of a "tower" of new indices (with `.right == null`)
   * @return `true` iff we successfully inserted the new indices
   */
  private[this] final def addIndices(_q: Index, _skips: Int, x: Index): Boolean = {
    if (x ne null) {
      var q = _q
      var skips = _skips
      val z = x.node
      if ((z ne null) && !z.isMarker && (q ne null)) {
        var retrying = false
        while (true) { // find splice point
          val r = q.getRight()
          var c: Int = 0 // comparison result
          if (r ne null) {
            val p = r.node
            if (p.isMarker || p.isDeleted()) {
              // clean deleted node:
              q.casRight(r, r.getRight())
              c = 0
            } else {
              c = cpr(z.key, p.key)
            }
            if (c > 0) {
              q = r
            } else if (c == 0) {
              // stale
              return false // scalafix:ok
            }
          } else {
            c = -1
          }

          if (c < 0) {
            val d = q.down
            if ((d ne null) && (skips > 0)) {
              skips -= 1
              q = d
            } else if ((d ne null) && !retrying && !addIndices(d, 0, x.down)) {
              return false // scalafix:ok
            } else {
              x.setRightPlain(r) // CAS piggyback
              if (q.casRight(r, x)) {
                return true // scalafix:ok
              } else {
                retrying = true // re-find splice point
              }
            }
          }
        }
      }
    }

    false
  }

  /**
   * Returns a base node with key < `key`. Also unlinks
   * indices to deleted nodes while searching.
   *
   * Analogous to `findPredecessor` in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def findPredecessor(key: K): Node = {
    var q: Index = _head.getAcquire() // current index node
    if ((q eq null) || isMARKER(key)) {
      null
    } else {
      while (true) {
        // go right:
        q = walkRight(q, key)
        // go down:
        val d = q.down
        if (d ne null) {
          q = d
        } else {
          // can't go down, we're done:
          return q.node // scalafix:ok
        }
      }

      null // unreachable
    }
  }

  /**
   * Tries to unlink the (logically) already deleted node
   * `n` from its predecessor `b`. Before unlinking, this
   * method inserts a "marker" node after `n`, to make
   * sure there are no lost concurrent inserts. (An insert
   * would do a CAS on `n.next`; linking a marker node after
   * `n` makes sure the concurrent CAS on `n.next` will fail.)
   *
   * When this method returns, `n` is already unlinked
   * from `b` (either by this method, or a concurrent
   * thread).
   *
   * `b` or `n` may be `null`, in which case this method
   * is a no-op.
   *
   * Analogous to `unlinkNode` in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def unlinkNode(b: Node, n: Node): Unit = {
    if ((b ne null) && (n ne null)) {
      // makes sure `n` is marked,
      // returns node after the marker
      def mark(): Node = {
        val f = n.getNext()
        if ((f ne null) && f.isMarker) {
          f.getNext() // `n` is already marked
        } else if (n.casNext(f, new Node(MARKER, TOMB, f))) {
          f // we've successfully marked `n`
        } else {
          mark() // lost race, retry
        }
      }

      val p = mark()
      b.casNext(n, p)
      // if this CAS failed, someone else already unlinked the marked `n`
      ()
    }
  }

  /**
   * Tries to reduce the number of levels by removing
   * the topmost level.
   *
   * Multiple conditions must be fulfilled to actually
   * remove the level: not only the topmost (1st) level
   * must be (likely) empty, but the 2nd and 3rd too.
   * This is to (1) reduce the chance of mistakes (see
   * below), and (2) reduce the chance of frequent
   * adding/removing of levels (hysteresis).
   *
   * We can make mistakes here: we can (with a small
   * probability) remove a level which is concurrently
   * becoming non-empty. This can degrade performance,
   * but does not impact correctness (e.g., we won't
   * lose keys/values). To even further reduce the
   * possibility of mistakes, if we detect one, we
   * try to quickly undo the deletion we did.
   *
   * The reason for (rarely) allowing the removal of a
   * level which shouldn't be removed, is that this is
   * still better than allowing levels to just grow
   * (which would also degrade performance).
   *
   * Analogous to `tryReduceLevel` in the JSR-166 `ConcurrentSkipListMap`.
   */
  private[this] final def tryReduceLevel(): Unit = {
    val lv1 = _head.getAcquire()
    if ((lv1 ne null) && (lv1.getRight() eq null)) { // 1st level seems empty
      val lv2 = lv1.down
      if ((lv2 ne null) && (lv2.getRight() eq null)) { // 2nd level seems empty
        val lv3 = lv2.down
        if ((lv3 ne null) && (lv3.getRight() eq null)) { // 3rd level seems empty
          // the topmost 3 levels seem empty,
          // so try to decrease levels by 1:
          if (_head.compareAndSet(lv1, lv2)) {
            // successfully reduced level,
            // but re-check if it's still empty:
            if (lv1.getRight() ne null) {
              // oops, we deleted a level
              // with concurrent insert(s),
              // try to fix our mistake:
              _head.compareAndSet(lv2, lv1)
              ()
            }
          }
        }
      }
    }
  }
}
