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
private[kcas] abstract class IBR2[T, M <: IBRManaged[T, M]](zeroEpoch: Long)
  extends IBRBase(zeroEpoch) {

  /** @return `true` iff `a` is really an `M` */
  protected[IBR2] def dynamicTest[A](a: A): Boolean

  protected[IBR2] def newThreadContext(): T

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
  private[IBR2] val reservations =
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

private[kcas] final object IBR2 {

  private[kcas] final val epochFreq = 128 // TODO

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
    global: IBR2[T, M],
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
     * Epoch interval reserved by the thread.
     *
     * Note: read by other threads when reclaiming memory!
     */
    private[IBR2] final val reservation: IBRReservation =
      new IBRReservation(Long.MaxValue)

    /** For testing */
    private[kcas] final def snapshotReservation: SnapshotReservation = {
      SnapshotReservation(
        lower = this.reservation.getLower(),
        upper = this.reservation.getUpper()
      )
    }

    /** For testing */
    private[kcas] final def globalContext: IBR2[T, M] =
      global

    /** For testing */
    private[kcas] final def op[A](body: => A): A = {
      this.startOp()
      try { body } finally { this.endOp() }
    }

    final def alloc(elem: M): Unit = {
      this.counter += 1
      val epoch = if ((this.counter % epochFreq) == 0) {
        this.global.incrementEpoch()
      } else {
        this.global.getEpoch()
      }
      if (epoch > this.reservation.getUpperPlain()) {
        this.reservation.setUpper(epoch)
      }
      // plain: will be published with release/volatile
      elem.setBirthEpochPlain(epoch)
      elem.setRetireEpochPlain(epoch)
    }

    final def startOp(): Unit = {
      reserveEpoch(this.global.getEpoch())
    }

    final def endOp(): Unit = {
      reserveEpoch(Long.MaxValue)
    }

    @tailrec
    final def readAcquire[A](ref: AtomicReference[A]): A = {
      val a: A = ref.getAcquire()
      if (tryAdjustReservation(a)) a
      else readAcquire(ref) // retry
    }

    @tailrec
    final def readVolatileRef[A](ref: Ref[A]): A = {
      val a: A = ref.unsafeTryRead()
      if (tryAdjustReservation(a)) a
      else readVolatileRef(ref) // retry
    }

    private[kcas] final def tryAdjustReservation[A](a: A): Boolean = {
      if (this.global.dynamicTest(a)) {
        val m: M = a.asInstanceOf[M]
        val res: IBRReservation = this.reservation
        val currUpper = res.getUpper()
        // we read `m` (that is, `a`) with acquire, so we can read birthEpoch with plain:
        val mbe = m.getBirthEpochPlain()
        val ok1 = if (mbe > currUpper) {
          res.setUpper(mbe)
          // TODO: check if we still need this:
          // `m` might've been retired (and reused) before we adjusted
          // our reservation, so we have to recheck the birth epoch
          // (and opaque is not enough here), so we'll re-acquire from the ref:
          false
        } else {
          // no need to adjust our reservation
          true
        }
        val currLower = res.getLower()
        val mre = m.getRetireEpochPlain()
        val ok2 = if (mre < currLower) {
          res.setLower(mre)
          false
        } else { true }
        ok1 && ok2
      } else {
        // no need to adjust our reservation
        true
      }
    }

    final def write[A](ref: AtomicReference[A], nv: A): Unit = {
      ref.set(nv)
    }

    final def cas[A](ref: AtomicReference[A], ov: A, nv: A): Boolean = {
      ref.compareAndSet(ov, nv)
    }

    final def casRef[A](ref: Ref[A], ov: A, nv: A): Boolean = {
      ref.unsafeTryPerformCas(ov, nv)
    }

    /** For testing */
    private[kcas] final def isDuringOp(): Boolean = {
      (this.reservation.getLower() != Long.MaxValue) || (
        this.reservation.getUpper() != Long.MaxValue
      )
    }

    /** For testing */
    private[kcas] final def forceNextEpoch(): Unit = {
      assert(!this.isDuringOp())
      this.global.incrementEpoch()
      ()
    }

    private final def reserveEpoch(epoch: Long): Unit = {
      this.reservation.setLower(epoch)
      this.reservation.setUpper(epoch)
    }

    private[kcas] final def isInUseByOther(block: M): Boolean = {
      this.isInUseExcept(block, this)
    }

    private final def isInUseExcept(block: M, except: T): Boolean = {
      val it = this.global.reservations.values().iterator()

      @tailrec
      def isConflict(): Boolean = {
        if (it.hasNext()) {
          val wr = it.next()
          wr.get() match {
            case null =>
              it.remove()
              isConflict() // continue
            case tc =>
              if (equ(tc, except)) {
                // skip this one and continue
                isConflict()
              } else {
                val conflict = (
                  (block.getBirthEpochVolatile() <=  tc.reservation.getUpper()) &&
                  (block.getRetireEpochVolatile() >= tc.reservation.getLower())
                )
                if (conflict) true
                else isConflict() // continue
              }
          }
        } else {
          false
        }
      }

      isConflict()
    }
  }
}
