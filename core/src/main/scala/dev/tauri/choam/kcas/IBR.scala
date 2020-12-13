/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import java.lang.ref.{ WeakReference => WeakRef }
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentSkipListMap

import scala.annotation.tailrec

/**
 * Interval-based memory reclamation (TagIBR-TPA), based on
 * the paper by Haosen Wen, Joseph Izraelevitz, Wentao Cai,
 * H. Alan Beadle, and Michael L. Scott, section 3.2 ("Using a
 * Type Preserving Allocator").
 *
 * @see https://www.cs.rochester.edu/u/scott/papers/2018_PPoPP_IBR.pdf
 * @see https://github.com/urcs-sync/Interval-Based-Reclamation
 *
 * @param `zeroEpoch` is the value of the very first epoch.
*/
private[kcas] abstract class IBR[T, M <: IBRManaged[T, M]](zeroEpoch: Long)
  extends IBRBase(zeroEpoch) {

  /** @return `true` iff `a` is really an `M` */
  protected[IBR] def dynamicTest[A](a: A): Boolean

  /** @return a newly allocated object */
  protected[IBR] def allocateNew(): M

  protected[IBR] def newThreadContext(): T

  /** For testing */
  private[kcas] def epochNumber: Long =
    this.getEpoch()

  /**
   * Reservations of all the (active) threads
   *
   * Threads hold a strong reference to their
   * `ThreadContext` in a thread local. Thus,
   * we only need a weakref here. If a thread
   * dies, its thread locals are cleared, so
   * the context can be GC'd (by the JVM). Empty
   * weakrefs are removed in `ThreadContext#empty`.
   *
   * Removing a dead thread's reservation will not
   * affect safety, because a dead thread will never
   * continue its current op (if any).
   */
  private[IBR] val reservations =
    new ConcurrentSkipListMap[Long, WeakRef[T]]

  /** For testing */
  private[kcas] def snapshotReservations: Map[Long, WeakRef[T]] = {
    @tailrec
    def go(
      it: java.util.Iterator[java.util.Map.Entry[Long, WeakRef[T]]],
      acc: Map[Long, WeakRef[T]]
    ): Map[Long, WeakRef[T]] = {
      if (it.hasNext()) {
        val n = it.next()
        go(it, acc.updated(n.getKey(), n.getValue()))
      } else {
        acc
      }
    }
    go(this.reservations.entrySet().iterator(), Map.empty)
  }

  /** Holds the context for each (active) thread */
  private[this] val threadContextKey =
    new ThreadLocal[T]()

  /** Gets of creates the context for the current thread */
  def threadContext(): T = {
    threadContextKey.get() match {
      case null =>
        val tc = this.newThreadContext()
        threadContextKey.set(tc)
        this.reservations.put(
          Thread.currentThread().getId(),
          new WeakRef(tc)
        )
        tc
      case tc =>
        tc
    }
  }
}

private[kcas] final object IBR {

  private[kcas] final val epochFreq = 128 // TODO

  private[kcas] final val emptyFreq = 256 // TODO

  assert(emptyFreq > epochFreq) // FIXME

  private[kcas] final val maxFreeListSize = 64 // TODO

  /** For testing */
  private[kcas] final case class SnapshotReservation(
    lower: Long,
    upper: Long
  )

  /**
   * Thread-local context of a thread
   *
   * Note: some fields can be accessed by other threads!
   */
  abstract class ThreadContext[T <: ThreadContext[T, M], M <: IBRManaged[T, M]](
    global: IBR[T, M],
    startCounter: Int = 0
  ) { this: T =>

    /**
     * Allocation counter for incrementing the epoch and running reclamation
     *
     * Overflow doesn't really matter, we only use it modulo `epochFreq` or `emptyFreq`.
     */
    private[this] var counter: Int =
      startCounter

    /**
     * Intrusive linked list of retired (but not freed) objects
     *
     * It contains `retiredCount` items.
     */
    private[this] var retired: M =
      nullOf[M]

    /** Current size of the `retired` list */
    private[this] var retiredCount: Long =
      0L

    // TODO: Maybe add a failsafe: if `retiredCount`
    // TODO: is abnormally big, release some objects
    // TODO: without `free`-ing them. Since we'll use
    // TODO: IBR to remove EMCAS descriptors, this
    // TODO: shouldn't cause correctness problems, and
    // TODO: would avoid leaking a lot of memory if
    // TOOD: something goes terribly wrong.

    /**
     * Intrusive linked list of freed (reusable) objects
     *
     * It contains `freeListSize` items (at most `maxFreeListSize`).
     */
    private[this] var freeList: M =
      nullOf[M]

    private[this] var freeListSize: Long =
      0L

    /**
     * Epoch interval reserved by the thread.
     *
     * Note: read by other threads when reclaiming memory!
     */
    private[IBR] final val reservation: IBRReservation =
      new IBRReservation(Long.MaxValue)

    /** For testing */
    private[kcas] final def snapshotReservation: SnapshotReservation = {
      SnapshotReservation(
        lower = this.reservation.getLower(),
        upper = this.reservation.getUpper()
      )
    }

    /** For testing */
    private[kcas] final def globalContext: IBR[T, M] =
      global

    /** For testing */
    private[kcas] final def op[A](body: => A): A = {
      this.startOp()
      try { body } finally { this.endOp() }
    }

    final def alloc(): M = {
      this.counter += 1
      if ((this.counter % epochFreq) == 0) {
        this.global.incrementEpoch()
      }
      val elem = if (this.freeList ne null) {
        this.freeListSize -= 1
        val elem = this.freeList
        this.freeList = elem.getNext()
        elem.setNext(nullOf[M])
        elem
      } else {
        global.allocateNew()
      }
      elem.setBirthEpochOpaque(this.global.getEpoch()) // opaque: will be published with release
      elem.allocate(this)
      elem
    }

    final def retire(a: M): Unit = {
      this.retiredCount += 1L
      a.setNext(this.retired)
      this.retired = a
      a.setRetireEpochOpaque(this.global.getEpoch()) // opaque: was read with acquire
      a.retire(this)
      if ((this.counter % emptyFreq) == 0) {
        this.empty()
      }
    }

    final def startOp(): Unit = {
      reserve(this.global.getEpoch())
    }

    final def endOp(): Unit = {
      reserve(Long.MaxValue)
    }

    @tailrec
    final def readVhAcquire[A](vh: VarHandle, obj: M): A = {
      val a: A = vh.getAcquire(obj)
      if (tryAdjustReservation(a)) a
      else readVhAcquire(vh, obj) // retry
    }

    @tailrec
    final def readAcquire[A](ref: AtomicReference[A]): A = {
      val a: A = ref.getAcquire()
      if (tryAdjustReservation(a)) a
      else readAcquire(ref) // retry
    }

    private[kcas] final def tryAdjustReservation[A](a: A): Boolean = {
      if (this.global.dynamicTest(a)) {
        val m: M = a.asInstanceOf[M]
        val res: IBRReservation = this.reservation
        val currUpper = res.getUpper()
        // we read `m` (that is, `a`) with acquire, so we can read birthEpoch with opaque:
        val mbe = m.getBirthEpochOpaque()
        if (mbe > currUpper) {
          res.setUpper(mbe)
          // `m` might've been retired (and reused) before we adjusted
          // our reservation, so we have to recheck the birth epoch
          // (and opaque is not enough here), so we'll re-acquire from the ref:
          false
        } else {
          // no need to adjust our reservation
          true
        }
      } else {
        // no need to adjust our reservation
        true
      }
    }

    // TODO: When writing or CAS-ing, the previous descriptor
    // in the ref could still be in use. Thus, we need to include
    // its whole interval in the interval of the new descriptor.
    // (And writing or CAS-ing a non-descriptor is not allowed
    // in that case.)

    final def writeVh[A](vh: VarHandle, obj: M, a: A): Unit = {
      vh.setVolatile(obj, a)
    }

    final def write[A](ref: AtomicReference[A], nv: A): Unit = {
      ref.set(nv)
    }

    final def casVh[A](vh: VarHandle, obj: M, ov: A, nv: A): Boolean = {
      vh.compareAndSet(obj, ov, nv)
    }

    final def cas[A](ref: AtomicReference[A], ov: A, nv: A): Boolean = {
      ref.compareAndSet(ov, nv)
    }

    /** For testing */
    private[kcas] final def isDuringOp(): Boolean = {
      (this.reservation.getLower() != Long.MaxValue) || (
        this.reservation.getUpper() != Long.MaxValue
      )
    }

    /** For testing */
    private[kcas] final def getRetiredCount(): Long = {
      this.retiredCount
    }

    /** For testing */
    private[kcas] final def forceGc(): Unit = {
      assert(this.isDuringOp())
      this.empty()
    }

    /** For testing */
    private[kcas] final def forceDebugGc(): List[(M, (T, Long, Long))] = {
      assert(this.isDuringOp())
      val conflicts = this.emptyDebug()
      conflicts.reverse
    }

    /** For testing */
    private[kcas] final def forceNextEpoch(): Unit = {
      assert(!this.isDuringOp())
      this.global.incrementEpoch()
      ()
    }

    /** For testing */
    private[kcas] final def fullGc(): Unit = {
      forceNextEpoch()
      startOp()
      try forceGc()
      finally endOp()
    }

    /** For testing */
    private[kcas] final def fullDebugGc(): List[(M, (T, Long, Long))] = {
      forceNextEpoch()
      startOp()
      try forceDebugGc()
      finally endOp()
    }

    private final def reserve(epoch: Long): Unit = {
      this.reservation.setLower(epoch)
      this.reservation.setUpper(epoch)
    }

    private final def empty(): Unit = {
      val reservations = this.global.reservations.values()
      @tailrec
      def go(curr: M, prev: M): Unit = {
        if (curr ne null) {
          val currNext = curr.getNext() // save next, because `free` may clear it
          var newPrev = curr // `prev` for the next iteration
          if (!isConflict(curr, reservations.iterator())) {
            // remove `curr` from the `retired` list:
            this.retiredCount -= 1L
            if (prev ne null) {
              // delete an internal item:
              prev.setNext(curr.getNext())
            } else {
              // delete the head:
              this.retired = curr.getNext()
            }
            free(curr) // actually free `curr`
            newPrev = prev // since we removed `curr` form the list
          }
          go(currNext, newPrev)
        } // else: end of `retired` list
      }
      @tailrec
      def isConflict(block: M, it: java.util.Iterator[WeakRef[T]]): Boolean = {
        if (it.hasNext()) {
          val wr = it.next()
          wr.get() match {
            case null =>
              it.remove()
              isConflict(block, it) // continue
            case tc =>
              // block is currently threadlocal, we can read with opaque
              val conflict = (
                (block.getBirthEpochOpaque() <=  tc.reservation.getUpper()) &&
                (block.getRetireEpochOpaque() >= tc.reservation.getLower())
              )
              if (conflict) true
              else isConflict(block, it) // continue
          }
        } else {
          false
        }
      }

      go(this.retired, prev = nullOf[M])
    }

    /** For testing and debugging */
    private final def emptyDebug(): List[(M, (T, Long, Long))] = {
      val reservations = this.global.reservations.values()
      @tailrec
      def goDebug(curr: M, prev: M, conflicts: List[(M, (T, Long, Long))]): List[(M, (T, Long, Long))] = {
        if (curr ne null) {
          val currNext = curr.getNext() // save next, because `free` may clear it
          var newPrev = curr // `prev` for the next iteration
          val conflict = isConflictDebug(curr, reservations.iterator())
          if (conflict eq null) {
            // no conflict, remove `curr` from the `retired` list:
            this.retiredCount -= 1L
            if (prev ne null) {
              // delete an internal item:
              prev.setNext(curr.getNext())
            } else {
              // delete the head:
              this.retired = curr.getNext()
            }
            free(curr) // actually free `curr`
            newPrev = prev // since we removed `curr` form the list
            goDebug(currNext, newPrev, conflicts)
          } else {
            // found a conflict
            goDebug(currNext, newPrev, (curr, conflict) :: conflicts)
          }
        } else {
          // end of `retired` list
          conflicts
        }
      }
      @tailrec
      def isConflictDebug(block: M, it: java.util.Iterator[WeakRef[T]]): (T, Long, Long) = {
        if (it.hasNext()) {
          val wr = it.next()
          wr.get() match {
            case null =>
              it.remove()
              isConflictDebug(block, it) // continue
            case tc =>
              // block is currently threadlocal, we can read with opaque
              val birthEpoch = block.getBirthEpochOpaque()
              val upper = tc.reservation.getUpper()
              if (birthEpoch <= upper) {
                val retireEpoch = block.getRetireEpochOpaque()
                val lower = tc.reservation.getLower()
                if (retireEpoch >= lower) {
                  (tc, lower, upper) // found a conflict, return it
                } else {
                  isConflictDebug(block, it) // continue
                }
              } else {
                isConflictDebug(block, it) // continue
              }
          }
        } else {
          null // no conflict
        }
      }

      goDebug(this.retired, prev = nullOf[M], conflicts = Nil)
    }

    private final def free(block: M): Unit = {
      block.free(this)
      block.setBirthEpochOpaque(Long.MinValue) // TODO: not strictly necessary
      block.setRetireEpochOpaque(Long.MaxValue) // TODO: not strictly necessary
      if (this.freeListSize < maxFreeListSize) {
        this.freeListSize += 1
        block.setNext(this.freeList)
        this.freeList = block
      } else {
        block.setNext(nullOf[M])
      }
    }
  }
}
