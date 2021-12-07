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
package mcas

import java.lang.ref.{ WeakReference => WeakRef }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentSkipListMap

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
private abstract class IBR[T](zeroEpoch: Long)
  extends IBRBase(zeroEpoch) {

  protected[IBR] def newThreadContext(): T

  /** For testing */
  private[mcas] def epochNumber: Long =
    this.getEpoch()

  /**
   * Reservations of all the (active) threads
   *
   * Threads hold a strong reference to their
   * `ThreadContext` in a thread local. Thus,
   * we only need a weakref here. If a thread
   * dies, its thread locals are cleared, so
   * the context can be GC'd (by the JVM). Empty
   * weakrefs are removed in `ThreadContext#isInUseByOther`.
   *
   * Removing a dead thread's reservation will not
   * affect safety, because a dead thread will never
   * continue its current op (if any).
   */
  private[IBR] val reservations =
    new ConcurrentSkipListMap[Long, WeakRef[T]]

  /** For testing */
  private[mcas] def snapshotReservations: Map[Long, WeakRef[T]] = {
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

private object IBR {

  private[mcas] final val epochFreq = 128 // TODO

  /** For testing */
  private[mcas] final case class SnapshotReservation(
    lower: Long,
    upper: Long
  )

  /**
   * Thread-local context of a thread
   *
   * Note: some fields can be accessed by other threads!
   */
  abstract class ThreadContext[T <: ThreadContext[T]](
    global: IBR[T],
    startCounter: Int = 0
  ) { this: T =>

    /**
     * Allocation counter for incrementing the epoch and running reclamation
     *
     * Overflow doesn't really matter, we only use it modulo `epochFreq` or `emptyFreq`.
     */
    private[this] var counter: Int =
      startCounter

    /** For testing */
    private[mcas] def resetCounter(to: Int): Unit =
      this.counter = to

    /**
     * Epoch interval reserved by the thread.
     *
     * Note: read by other threads when reclaiming memory!
     */
    private[IBR] final val reservation: IBRReservation =
      new IBRReservation(Long.MaxValue)

    /** For testing */
    private[mcas] final def snapshotReservation: SnapshotReservation = {
      SnapshotReservation(
        lower = this.reservation.getLowerAcquire(),
        upper = this.reservation.getUpperAcquire()
      )
    }

    /** For testing */
    private[mcas] final def globalContext: IBR[T] =
      global

    /** For testing */
    private[mcas] final def op[A](body: => A): A = {
      this.startOp()
      try { body } finally { this.endOp() }
    }

    final def alloc(elem: IBRManaged[_, _]): Unit = {
      assert(this.isDuringOp()) // TODO: remove this debug assertion
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
      elem.setMinEpochPlain(epoch)
      elem.setMaxEpochPlain(epoch)
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
    final def readVolatileRef[A](ref: MemoryLocation[A]): A = {
      val a: A = ref.unsafeGetVolatile()
      if (tryAdjustReservation(a)) a
      else readVolatileRef(ref) // retry
    }

    private[mcas] final def tryAdjustReservation[A](a: A): Boolean = {
      if (a.isInstanceOf[IBRManaged[_, _]]) {
        val m: IBRManaged[_, _] = a.asInstanceOf[IBRManaged[_, _]]
        val res: IBRReservation = this.reservation
        val currUpper = res.getUpperAcquire()
        // we read `m` (that is, `a`) with acquire, so we can read minEpoch with plain:
        val min = m.getMinEpochPlain()
        val ok1 = if (min > currUpper) {
          res.setUpper(min)
          // we'll have to re-check the min/max epoch,
          // because between reading the min/max epoch,
          // and writing it to our reservation, someone else
          // might've determined that `m` is not in use
          false
        } else {
          // no need to adjust our reservation, so no need to re-check
          true
        }
        val currLower = res.getLowerAcquire()
        // as above, plain is enough here:
        val max = m.getMaxEpochPlain()
        val ok2 = if (max < currLower) {
          res.setLower(max)
          // as above, we'll have to re-check:
          false
        } else {
          // as above, no need to re-check
          true
        }
        ok1 && ok2 // no re-check needed if both were already correct
      } else {
        // not a descriptor, no need to adjust our reservation or re-check
        true
      }
    }

    final def write[A](ref: AtomicReference[A], nv: A): Unit = {
      ref.set(nv)
    }

    final def cas[A](ref: AtomicReference[A], ov: A, nv: A): Boolean = {
      ref.compareAndSet(ov, nv)
    }

    final def casRef[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      ref.unsafeCasVolatile(ov, nv)
    }

    /** For testing */
    private[mcas] final def isDuringOp(): Boolean = {
      (this.reservation.getLowerAcquire() != Long.MaxValue) || (
        this.reservation.getUpperAcquire() != Long.MaxValue
      )
    }

    /** For testing */
    private[mcas] final def forceNextEpoch(): Unit = {
      assert(!this.isDuringOp())
      this.global.incrementEpoch()
      ()
    }

    private final def reserveEpoch(epoch: Long): Unit = {
      this.reservation.setLower(epoch)
      this.reservation.setUpper(epoch)
    }

    private[mcas] final def isInUseByOther(block: IBRManaged[_, _]): Boolean = {
      this.isInUseExcept(block, this)
    }

    private final def isInUseExcept(block: IBRManaged[_, _], except: T): Boolean = {
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
                  // We've read `block` from a `Ref`, thus if its min/max
                  // epochs change later, that is a spurious change we don't need.
                  // Because of this, a plain read is enough here.
                  (block.getMinEpochPlain() <= tc.reservation.getUpperAcquire()) &&
                  (block.getMaxEpochPlain() >= tc.reservation.getLowerAcquire())
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
