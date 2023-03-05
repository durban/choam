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

import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }
import java.lang.Long.{ MAX_VALUE, MIN_VALUE => MARKER }
import java.util.concurrent.ThreadLocalRandom

// TODO: For now this skip list is very specialized
// TODO: to callbacks and trigger times; we'll need
// TODO: to make it generic (and remove any assumptions
// TODO: and simplifications which are no longer valid).

/**
 * Concurrent skip list holding timer callbacks and their
 * associated trigger times. The 3 main operations are
 * `pollFirstIfTriggered`, `insert`, and the "remove"
 * returned by `insert` (for cancelling timers).
 */
final class TimerSkipList() extends AtomicLong(MARKER + 1L) { sequenceNumber =>

  /*
   * This implementation is based on the public
   * domain JSR-166 `ConcurrentSkipListMap`.
   * Contains simplifications, because we just
   * need the 3 main operations. Also,
   * `pollFirstIfTriggered` contains an extra
   * condition (compared to `pollFirstEntry`
   * in the JSR-166 implementation), because
   * we don't want to remove if the trigger time
   * is still in the future.
   *
   * Our values are the callbacks, and used
   * similarly. Our keys are essentially the
   * trigger times, but see the comment in
   * `insert`. Due to longs not having nulls,
   * we use a special value for designating
   * "marker" nodes (see `Node#isMarker`).
   */

  import TimerSkipList.{ Node, Index, Callback }

  /** The top left index node (or null if empty) */
  private[this] val head =
    new AtomicReference[Index]

  /**
   * Inserts a new `callback` which will be triggered not earlier
   * than `now + delay`. Returns a "canceller", which (if executed)
   * removes (cancels) the inserted callback. (Of course, by that
   * time the callback might've been already invoked.)
   *
   * @param now current time as returned by `System.nanoTime`
   * @param delay nanoseconds delay, must be nonnegative
   * @param callback the callback to insert into the skip list
   */
  final def insert(now: Long, delay: Long, callback: Right[Nothing, Unit] => Unit): Runnable = {
    require(delay >= 0L)
    // we have to check for overflow:
    val triggerTime = computeTriggerTime(now = now, delay = delay)
    // Because our skip list can't handle multiple
    // values (callbacks) for the same key, the
    // key is not only the `triggerTime`, but a
    // conceptually a `(triggerTime, seqNo)` tuple.
    // We generate unique (for this skip list)
    // sequence numbers with an atomic counter.
    val seqNo = {
      val sn = sequenceNumber.getAndIncrement()
      // In case of overflow (very unlikely),
      // we make sure we don't use MARKER for
      // a valid node (which would be very bad);
      // otherwise the overflow can only cause
      // problems with the ordering of callbacks
      // with the exact same triggerTime...
      // which is unspecified anyway (due to
      // stealing).
      if (sn != MARKER) sn
      else sequenceNumber.getAndIncrement()
    }
    doPut(triggerTime, seqNo, callback)
    new Canceller(triggerTime, seqNo)
  }

  private[skiplist] final class Canceller(val triggerTime: Long, val seqNo: Long) extends Runnable {
    final override def run(): Unit = {
      TimerSkipList.this.doRemove(triggerTime, seqNo)
    }
  }

  /**
   * Removes and returns the first (earliest) timer callback,
   * if its trigger time is not later than `now`. Can return
   * `null` if there is no such callback.
   *
   * It is the caller's responsibility to check for `null`,
   * and actually invoke the callback (if desired).
   */
  final def pollFirstIfTriggered(now: Long): Right[Nothing, Unit] => Unit = {
    doRemoveFirstNodeIfTriggered(now)
  }

  final override def toString: String = {
    peekFirstNode() match {
      case null =>
        "TimerSkipList()"
      case _ =>
        "TimerSkipList(...)"
    }

  }

  private[skiplist] final def printBaseNodesQuiescent(println: String => Unit): Unit = {
    var n = baseHead()
    while (n ne null) {
      val cb = n.getCb() match {
        case null => "null"
        case cb => cb.##.toHexString
      }
      println(s"${n.triggerTime}, ${n.sequenceNum}, ${cb}")
      n = n.getNext()
    }
  }

  private[skiplist] final def peekFirstQuiescent(): Callback = {
    val n = peekFirstNode()
    if (n ne null) {
      n.getCb()
    } else {
      null
    }
  }

  /**
   * Compares keys, first by trigger time, then by
   * sequence number; this method determines the "total
   * order" that is used by the skip list.
   *
   * The trigger times are `System.nanoTime` longs, so they
   * have to be compared in a peculiar way (see javadoc there).
   * This makes this order non-transitive, which is quite bad.
   * However, `computeTriggerTime` makes sure that there is
   * no overflow here, so we're okay.
   */
  private[this] final def cpr(xTriggerTime: Long, xSeqNo: Long, yTriggerTime: Long, ySeqNo: Long): Int = {
    // first compare trigger times:
    val d = xTriggerTime - yTriggerTime
    if (d < 0) -1
    else if (d > 0) 1
    else {
      // if times are equal, compare seq numbers:
      if (xSeqNo < ySeqNo) -1
      else if (xSeqNo == ySeqNo) 0
      else 1
    }
  }

  /**
   * Computes the trigger time in an overflow-safe manner.
   * The trigger time is essentially `now + delay`. However,
   * we must constrain all trigger times in the skip list
   * to be within `Long.MaxValue` of each other (otherwise
   * there will be overflow when comparing in `cpr`). Thus,
   * if `delay` is so big, we'll reduce it to the greatest
   * allowable (in `overflowFree`).
   *
   * From the public domain JSR-166 `ScheduledThreadPoolExecutor`.
   */
  private[this] final def computeTriggerTime(now: Long, delay: Long): Long = {
    val safeDelay = if (delay < (MAX_VALUE >> 1)) delay else overflowFree(now, delay)
    now + safeDelay
  }

  /**
   * See `computeTriggerTime`. The overflow can happen if
   * a callback was already triggered (based on `now`), but
   * was not removed yet; and `delay` is sufficiently big.
   *
   * From the public domain JSR-166 `ScheduledThreadPoolExecutor`.
   */
  private[this] final def overflowFree(now: Long, delay: Long): Long = {
    val head = peekFirstNode()
    // Note, that there is a race condition here:
    // the node we're looking at (`head`) can be
    // concurrently removed/cancelled. But the
    // consequence of that here is only that we
    // will be MORE careful with `delay` than
    // necessary.
    if (head ne null) {
      val headDelay = head.triggerTime - now
      if ((headDelay < 0) && (delay - headDelay < 0)) {
        // head was already triggered, and `delay` is big enough,
        // so we must clamp `delay`:
        MAX_VALUE + headDelay
      } else {
        delay
      }
    } else {
      delay // empty
    }
  }

  @tailrec
  private[this] final def doPut(triggerTime: Long, seqNo: Long, cb: Callback): Unit = {
    val h = head.getAcquire()
    var levels = 0
    var b: Node = if (h eq null) {
      // head not initialized yet, do it now;
      // first node of the base list is a sentinel
      // (without payload):
      val base = new Node(MARKER, MARKER, null: Callback, null)
      val h = new Index(base, null, null)
      if (head.compareAndSet(null, h)) base else null
    } else {
      // we have a head; find a node in the base list
      // "close to" (but before) the inserion point:
      var q: Index = h // current position, start from the head
      var foundBase: Node = null // we're looking for this
      while (foundBase eq null) {
        // first try to go right:
        var r: Index = q.getRight()
        while (r ne null) {
          val p = r.node
          if (p eq null) {
            val psn = p.sequenceNum
            if ((psn == MARKER) || (p.getCb() eq null)) {
              // marker or deleted node, unlink it:
              q.casRight(r, r.getRight())
              // and retry:
              r = q.getRight()
            } else if (cpr(triggerTime, seqNo, p.triggerTime, psn) > 0) {
              // we can still go right:
              q = r
              r = q.getRight()
            } else {
              // we must go down, break inner loop:
              r = null
            }
          } else if (cpr(triggerTime, seqNo, p.triggerTime, p.sequenceNum) > 0) {
            // we can still go right:
            q = r
            r = q.getRight()
          } else {
            // we must go down, break inner loop:
            r = null
          }
        }
        // then try to go down:
        val d = q.getDown()
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
      // `b` is a node in the base list, "close to"
      // but before the insertion point
      var z: Node = null // will be the new node when inserted
      var n: Node = null // next node
      var go = true
      while (go) {
        var c = 0
        n = b.getNext()
        if (n eq null) {
          c = -1
        } else if (n.sequenceNum == MARKER) {
          // someone is deleting `b` right now,
          // will restart insertion (`z` is still null)
          go = false
        } else if (n.getCb() eq null) {
          // `n` is logically deleted
          unlinkNode(b, n)
          c = 1 // will retry going right
        } else {
            c = cpr(triggerTime, seqNo, n.triggerTime, n.sequenceNum)
            if (c > 0) {
              // continue right
              b = n
            } // else: we assume c < 0, due to seqNr being unique
        }

        if (c < 0) {
          // found insertion point
          val p = new Node(triggerTime, seqNo, cb, n)
          if (b.casNext(n, p)) {
            z = p
            go = false
          } // else: lost a race, retry
        }
      }

      if (z ne null) {
        // we successfully inserted a new node;
        // maybe add extra indices:
        var rnd = ThreadLocalRandom.current().nextLong()
        if ((rnd & 0x3L) == 0L) { // add indices with 1/4 probability
          // first create a "tower" of index
          // nodes (all with `.right == null`):
          var skips = levels
          var x: Index = null // most recently created (topmost) index node in the tower
          var go = true
          while (go) { // create at most 62 indices in the tower
            x = new Index(z, x, null)
            if (rnd >= 0) {
              go = false
            } else {
              skips -= 1
              if (skips < 0) {
                go = false
              } else {
                rnd <<= 1
              }
            }
          }

          // then actually add these index nodes to the skiplist:
          if (addIndices(h, skips, x) && (skips < 0) && (head.getAcquire() eq h)) {
            // try to add new level:
            val hx = new Index(z, x, null)
            val nh = new Index(h.node, h, hx) // new head
            head.compareAndSet(h, nh)
          }

          if (z.getCb() eq null) {
            // was deleted while we added indices,
            // need to clean up:
            findPredecessor(triggerTime, seqNo)
            ()
          }
        }
      } else { // restart
        doPut(triggerTime, seqNo, cb)
      }
    } else { // restart
      doPut(triggerTime, seqNo, cb)
    }
  }

  /**
   * Finds the node with the specified key; deletes it
   * logically by CASing the callback to null; unlinks
   * it (first inserting a marker); removes associated
   * index nodes; and possibly reduces index level.
   */
  private[this] final def doRemove(triggerTime: Long, seqNo: Long): Unit = {
    var b = findPredecessor(triggerTime, seqNo)
    var done = false
    while ((b ne null) && !done) { // outer
      var inner = true
      while (inner) {
        val n = b.getNext()
        if (n eq null){
          inner = false
          b = null // break outer
        } else if (n.isMarker) {
          inner = false
          b = findPredecessor(triggerTime, seqNo)
        } else if (n.getCb() eq null) {
          unlinkNode(b, n)
        } else {
          val c = cpr(triggerTime, seqNo, n.triggerTime, n.sequenceNum)
          if (c > 0) {
            b = n
          } else if (c < 0) {
            inner = false
            b = null // break outer
          } else if (n.casCb(n.getCb(), null)) {
            // successfully logically deleted
            done = true
            inner = false
            unlinkNode(b, n)
            findPredecessor(triggerTime, seqNo) // cleanup
          }
        }
      }
    }

    if (done) {
      tryReduceLevel()
    }
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
        (n ne null) && (n.getCb() eq null)
      }) {
        b = n
      }

      n
    } else {
      null
    }
  }

  private[this] final def doRemoveFirstNodeIfTriggered(now: Long): Callback = {
    val b = baseHead()
    if (b ne null) {
      @tailrec
      def go(): Callback = {
        val n = b.getNext()
        if (n ne null) {
          val tt = n.triggerTime
          if (now - tt >= 0) { // triggered
            val cb = n.getCb()
            if (cb eq null) {
              // alread deleted node
              unlinkNode(b, n)
              go()
            } else if (n.casCb(cb, null)) {
              val sn = n.sequenceNum
              unlinkNode(b, n)
              tryReduceLevel()
              findPredecessor(tt, sn) // clean index
              cb
            } else {
              // lost race, retry
              go()
            }
          } else { // not triggered yet
            null
          }
        } else {
          null
        }
      }

      go()
    } else {
      null
    }
  }

  /** The head of the base list (or `null` if uninitialized) */
  private[this] final def baseHead(): Node = {
    val h = head.getAcquire()
    if (h ne null) h.node else null
  }

  /**
   * Adds indices after an insertion was performed (e.g. `doPut`).
   * TODO: iter-recurse
   *
   * Returns `false` on staleness, which disables higher-level
   * insertions.
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
          var c: Int = 0
          if (r ne null) {
            val p = r.node
            if ((p eq null) || p.isMarker || (p.getCb() eq null)) {
              q.casRight(r, r.getRight())
              c = 0
            } else {
              c = cpr(z.triggerTime, z.sequenceNum, p.triggerTime, p.sequenceNum)
            }
            if (c > 0) {
              q = r
            } else if (c == 0) {
              return false // scalafix:ok
            }
          } else {
            c = -1
          }

          if (c < 0) {
            val d = q.getDown()
            if ((d ne null) && (skips > 0)) {
              skips -= 1
              q = d
            } else if ((d ne null) && !retrying && !addIndices(d, 0, x.getDown())) {
              return false // scalafix:ok
            } else {
              x.setRight(r)
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
   * Returns a base node whith key < the parameters. Also unlinks
   * indices to deleted nodes while searching.
   */
  private[this] final def findPredecessor(triggerTime: Long, seqNo: Long): Node = {
    var q: Index = head.getAcquire() // current index node
    if ((q eq null) || (seqNo == MARKER)) {
      null
    } else {
      while (true) { // outer
        var r = q.getRight()
        while (r ne null) { // inner
          val p = r.node
          if (p eq null) {
            q.casRight(r, r.getRight())
            r = q.getRight()
          } else if (p.isMarker || (p.getCb() eq null)) {
            q.casRight(r, r.getRight())
            r = q.getRight()
          } else if (cpr(triggerTime, seqNo, p.triggerTime, p.sequenceNum) > 0) {
            q = r
            r = q.getRight()
          } else {
            // can't go right, break inner
            r = null
          }
        }
        // go down:
        val d = q.getDown()
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
   * sure there are no lost concurrent inserts.
   *
   * When this method returns, `n` is already unlinked
   * from `b` (either by this method, or a concurrent
   * thread).
   *
   * `b` or `n` may be `null`, in which case this method
   * is a no-op.
   */
  private[this] final def unlinkNode(b: Node, n: Node): Unit = {
    if ((b ne null) && (n ne null)) {
      // makes sure `n` is marked,
      // returns node after the marker
      def mark(): Node = {
        val f = n.getNext()
        if ((f ne null) && f.isMarker) {
          f.getNext() // `n` is already marked
        } else if (n.casNext(f, new Node(MARKER, MARKER, null: Callback, f))) {
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
   */
  private[this] final def tryReduceLevel(): Unit = {
    val h = head.getAcquire()
    if (h ne null) {
      if (h.getRight() eq null) { // 1st level seems empty
        val d = h.getDown()
        if (d ne null) {
          if (d.getRight() eq null) { // 2nd level seems empty
            val e = d.getDown()
            if (e ne null) {
              if (e.getRight() eq null) { // 3rd level seems empty
                // the topmost 3 levels seem empty,
                // so try to decrease levels by 1:
                if (head.compareAndSet(h, d)) {
                  // successfully reduced level,
                  // but re-check if it's still empty:
                  if (h.getRight() ne null) {
                    // oops, we deleted a level
                    // with concurrent insert(s),
                    // try to fix our mistake:
                    head.compareAndSet(d, h)
                    ()
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

final object TimerSkipList {

  private type Callback =
    Right[Nothing, Unit] => Unit

  /**
   * Base nodes (which form the base list).
   *
   * `next` is the next node in the base list (with a key > than this).
   *
   * A `Node` is a special "marker" node (for deletion) if `sequenceNum == MARKER`.
   * A `Node` is logically deleted if `cb eq null`.
   */
  private final class Node(
    val triggerTime: Long,
    val sequenceNum: Long,
    cb: AtomicReference[Callback],
    n: Node,
  ) extends AtomicReference[Node](n) { next =>

    def this(triggerTime: Long, seqNo: Long, cb: Callback, next: Node) = {
      this(triggerTime, seqNo, new AtomicReference(cb), next)
    }

    final def isMarker: Boolean = {
      // note: a marker node also has `triggerTime == MARKER`,
      // but that's also a valid trigger time, so we need
      // `sequenceNum` here
      sequenceNum == MARKER
    }

    final def getNext(): Node = {
      next.getAcquire()
    }

    final def casNext(ov: Node, nv: Node): Boolean = {
      next.compareAndSet(ov, nv)
    }

    final def getCb(): Callback = {
      cb.getAcquire()
    }

    final def casCb(ov: Callback, nv: Callback): Boolean = {
      cb.compareAndSet(ov, nv)
    }

    final override def toString: String =
      s"Node(${triggerTime}, ${sequenceNum}, ...)"
  }

  /** Index nodes */
  private final class Index(
    val node: Node,
    down: AtomicReference[Index],
    r: Index,
  ) extends AtomicReference[Index](r) { right =>

    def this(node: Node, down: Index, right: Index) = {
      this(node, new AtomicReference(down), right)
    }

    final def getDown(): Index = {
      down.getAcquire()
    }

    final def casDown(ov: Index, nv: Index): Boolean = {
      down.compareAndSet(ov, nv)
    }

    final def getRight(): Index = {
      right.getAcquire()
    }

    final def setRight(nv: Index): Unit = {
      right.set(nv)
    }

    final def casRight(ov: Index, nv: Index): Boolean = {
      right.compareAndSet(ov, nv)
    }

    final override def toString: String =
      "Index(...)"
  }
}
