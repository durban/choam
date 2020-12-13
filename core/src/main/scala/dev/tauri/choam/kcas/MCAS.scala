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

import java.lang.ThreadLocal
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference

import scala.annotation.elidable

// TODO: Figure out some zero-cost way to safely work
// TODO: with nullable things (e.g., Nullable[MCASEntry]).

// TODO: elide assertions during benchmarks

/**
 * An optimized version of `CASN`.
 *
 * Reference counting implementation is based on
 * [Correction of a Memory Management Method for Lock-Free Data Structures](
 * http://www.dtic.mil/cgi-bin/GetTRDoc?AD=ADA309143) by Maged M. Michael and
 * Michael L. Scott.
 */
private[kcas] object MCAS extends KCAS { self =>

  override def start(): self.Desc =
    startInternal()

  private[MCAS] def startInternal(): MCASDesc = {
    val desc = TlSt.get().loanDescriptor()
    desc.start()
    desc
  }

  override def tryReadOne[A](ref: Ref[A]): A =
    internalRead(ref)

  private sealed trait RDCSSResult
  private final case object AcquireSuccess extends RDCSSResult
  private final case object AcquireFailure extends RDCSSResult

  @tailrec
  private def internalRead[A](ref: Ref[A]): A = {
    val r = RDCSSRead(ref)
    r match {
      case d: MCASDesc =>
        // help the other op:
        d.incr()
        if (equ(ref.unsafeTryRead(), d.as[A])) {
          assert(!d.isLsbSet(), "LSB of descriptor we incr'd is set")
          d.perform()
        } else {
          d.decr()
        }
        // and retry:
        internalRead(ref)
      case _ =>
        // ok, we found it:
        r
    }
  }

  private def RDCSSRead[A](ref: Ref[A]): A = {
    @tailrec
    def go(): A = {
      val r: A = ref.unsafeTryRead()
      (r : Any) match {
        case e: MCASEntry =>
          // try to help the other thread, then retry:
          val desc = e.desc
          if (desc ne null) {
            desc.incr()
            if (equ(ref.unsafeTryRead(), r) && (e.desc eq desc)) {
              assert(!desc.isLsbSet(), "LSB of descriptor we incr'd is set")
              try {
                RDCSSComp(desc.status, e, desc)
              } finally {
                desc.decr()
              }
            } else {
              desc.decr()
            }
          } // else: was released, we can retry
          go()
        case _ =>
          // ok:
          r
      }
    }

    go()
  }

  private def RDCSSComp[A](
    status: Ref[MCASStatus],
    entry: MCASEntry,
    nv: MCASDesc
  ): Unit = {
    val s: MCASStatus = status.unsafeTryRead()
    if (s eq Undecided) {
      CAS1fromEntry[entry.A](entry.ref, entry, nv.as[entry.A])
    } else {
      CAS1fromEntry(entry.ref, entry, entry.ov)
    }
    // We don't care whether the CAS succeeded,
    // since it's possible that another thread
    // helped us, and completed the operation
    // before us.
    ()
  }

  private def CAS1toEntry[A](ref: Ref[A], ov: A, nv: MCASEntry): A =
    ref.unsafeTryPerformCmpxchg(ov, nv.as[A])

  private def CAS1fromEntry[A](ref: Ref[A], ov: MCASEntry, nv: A): Boolean =
    ref.unsafeTryPerformCas(ov.as[A], nv)

  private def CAS1fromDesc[A](ref: Ref[A], ov: MCASDesc, nv: A): Boolean =
    ref.unsafeTryPerformCas(ov.as[A], nv)

  private sealed trait MCASStatus
  private final case object Undecided extends MCASStatus
  private sealed trait Decided extends MCASStatus
  private final case object Failed extends Decided
  private final case object Succeeded extends Decided

  private abstract class FreeList[A] {
    var next: A
  }

  private final class MCASDesc
      extends FreeList[MCASDesc]
      with self.Desc
      with RDCSSResult {

    private[this] val refcount =
      new AtomicInteger(1) // LSB is a claim flag

    private[MCAS] val status: Ref[MCASStatus] =
      Ref.mk(Undecided)

    // These `var`s doesn't need be volatile, since the
    // only way a thread can get its hands on a descriptor
    // is either
    // (1) by getting it from the thread-local freelist
    //     (in which case it's either brand new, or the
    //     fields were previously cleared by the same
    //     thread when it was released);
    // (2) or by getting it from a `Ref`, in which case
    //     the volatile read guarantees visibility.

    /** Next descriptor in the freelist (if `this` is not currently used) */
    override var next: MCASDesc =
      _

    /** The head of the list of entries in the decriptor (or `null` if currently empty) */
    private[this] var head: MCASEntry =
      _

    /** Number of entries currently in this descriptor (the length of the list at `head`) */
    private[this] var k: Int =
      0

    def rawRefCnt(): Int =
      refcount.get()

    def incr(): Unit = {
      refcount.addAndGet(2)
      ()
    }

    def decr(): Unit = {
      if (decrementAndTestAndSet()) {
        release()
      }
    }

    def isLsbSet(): Boolean =
      (refcount.get() % 2) == 1

    /**
     * @return true iff the (logical) refcount reached 0
     */
    @tailrec
    private def decrementAndTestAndSet(): Boolean = {
      val ov: Int = refcount.get()
      val nv: Int = if (ov == 2) 1 else ov - 2
      if (refcount.compareAndSet(ov, nv)) {
        ov == 2
      } else {
        decrementAndTestAndSet()
      }
    }

    /**
     * Actually release the descriptor.
     *
     * Must not be called if `decrementAndTestAndSet` returns `false`.
     */
    private def release(): Unit = {
      // release entries:
      val tlst = TlSt.get()
      while (head ne null) {
        val e = head
        head = e.next
        tlst.releaseEntry(e)
        k -= 1
      }
      assert(k == 0, s"k of empty descriptor is not zero, but ${k}")
      // release descriptor:
      tlst.releaseDescriptor(this)
    }

    @tailrec
    def clearLsb(): Unit = {
      val ov: Int = refcount.get()
      assert((ov % 2) == 1, "lowest bit is not set")
      val nv = ov - 1
      if (!refcount.compareAndSet(ov, nv)) {
        clearLsb()
      }
    }

    def start(): Unit = {
      assert(head eq null, "head of new descriptor is not null")
      assert(k == 0, "k of new descriptor is not zero")
      status.unsafeSet(Undecided)
    }

    override def withCAS[A](ref: Ref[A], ov: A, nv: A): self.Desc = {
      // TODO: verify if accessing the thread locals is too slow
      val entry = TlSt.get().loanEntry[A]()
      entry.ref = ref
      entry.ov = ov
      entry.nv = nv
      entry.desc = this
      entry.next = head
      head = entry
      k += 1
      this
    }

    private[MCAS] def withEntries(head: MCASEntry): Unit = {
      assert(this.k == 0, s"loading snapshot into non-empty descriptor (k is ${k})")
      assert(this.head eq null, "loading snapshot into non-empty descriptor (head != null)")
      this.head = head
      this.k = copyIfNeeded()
    }

    override def snapshot(): self.Snap = {
      if (head eq null) {
        EmptySnapshot
      } else {
        head.snaps += 1
        head
      }
    }

    private def copyIfNeeded(): Int = {
      val tlst = TlSt.get()
      @tailrec
      def copyOrPass(curr: MCASEntry, prev: MCASEntry, copyAll: Boolean, count: Int): Int = {
        if (curr eq null) {
          count
        } else {
          if (copyAll || (curr.snaps > 0)) {
            val e = curr.copy(tlst)
            if (prev ne null) {
              prev.next = e
            } else {
              head = e
            }
            e.desc = this
            e.next = curr.next
            copyOrPass(curr.next, e, true, count + 1)
          } else {
            curr.desc = this
            copyOrPass(curr.next, curr, false, count + 1)
          }
        }
      }
      copyOrPass(head, null, false, 0)
    }

    override def tryPerform(): Boolean = {
      if (head eq null) {
        assert(k == 0, s"head is null, but k is ${k}")
        val ok = status.unsafeTryPerformCas(Undecided, Succeeded)
        assert(ok, "couldn't CAS the `status` of an empty descriptor")
        true
      } else {
        // we have entries (k > 0)
        copyIfNeeded()
        sort()
        perform()
      }
    }

    override def cancel(): Unit = {
      val tlst = TlSt.get()
      MCASEntry.releaseUnnededEntries(tlst, head)
      head = null
      k = 0

      if (decrementAndTestAndSet()) {
        // we could release (no one is using the descriptor),
        // but instead we return for possible reuse:
        // TODO: actually implement (and bench) reuse
        release()
      }
    }

    private def sort(): Unit = {
      def mergeSort(h: MCASEntry): MCASEntry = {
        if ((h eq null) || (h.next eq null)) {
          h
        } else {
          // return the head of the second half
          // TODO: we can use `k` to optimize this
          def split(h: MCASEntry): MCASEntry = {
            if ((h eq null) || (h.next eq null)) {
              null
            } else {
              var slow: MCASEntry = h
              var fast: MCASEntry = h.next
              while (fast ne null) {
                fast = fast.next
                if (fast ne null) {
                  slow = slow.next
                  fast = fast.next
                }
              }
              val res = slow.next
              slow.next = null
              res
            }
          }
          var a: MCASEntry = h
          var b: MCASEntry = split(h)
          a = mergeSort(a)
          b = mergeSort(b)

          @tailrec
          def merge(a: MCASEntry, b: MCASEntry, prev: MCASEntry): Unit = {
            if (a eq null) {
              prev.next = b
            } else if (b eq null) {
              prev.next = a
            } else {
              val cmp: Int = Ref.globalCompare(a.ref, b.ref)
              if (cmp < 0) {
                prev.next = a
                merge(a.next, b, prev = a)
              } else if (cmp > 0) {
                prev.next = b
                merge(a, b.next, prev = b)
              } else {
                // conflicting entries
                assert(a.ref eq b.ref)
                KCAS.impossibleKCAS[a.A, b.A](a.ref, a.ov, a.nv, b.ov, b.nv)
              }
            }
          }

          if (a eq null) b
          else if (b eq null) a
          else {
            val cmp: Int = Ref.globalCompare(a.ref, b.ref)
            if (cmp < 0) {
              merge(a.next, b, prev = a)
              a
            } else if (cmp > 0) {
              merge(a, b.next, prev = b)
              b
            } else {
              // conflicting entries
              val aa = a
              val bb = b
              assert(aa.ref eq bb.ref)
              KCAS.impossibleKCAS[aa.A, bb.A](aa.ref, aa.ov, aa.nv, bb.ov, bb.nv)
            }
          }
        }
      }

      head = mergeSort(head)
    }

    // Can be called from other threads!
    private[MCAS] def perform(): Boolean = {
      try {
        // Phase 1 starts from UNDECIDED:
        if (status.unsafeTryRead() eq Undecided) {
          @tailrec
          def phase1(entry: MCASEntry): Decided = {
            if (entry eq null) {
              Succeeded
            } else {
              val res = RDCSStoDesc(status, entry, this)
              res match {
                case that: MCASDesc =>
                  if (this ne that) {
                    // help the other op:
                    that.incr()
                    if (equ(entry.ref.unsafeTryRead(), that.as[entry.A])) {
                      assert(!that.isLsbSet())
                      that.perform()
                    } else {
                      that.decr()
                    }
                    // and retry from this entry:
                    phase1(entry)
                  } else {
                    // somebody helped us, continue:
                    phase1(entry.next)
                  }
                case AcquireFailure =>
                  // other op succeeded:
                  Failed
                case AcquireSuccess =>
                  // continue:
                  phase1(entry.next)
              }
            }
          }

          val decision: Decided = phase1(head)
          status.unsafeTryPerformCas(Undecided, decision)
        }

        // Phase 2 (now status is either FAILED or SUCCEEDED):
        val failOrSucc = status.unsafeTryRead() // TODO: try to avoid this read
        assert((failOrSucc eq Failed) || (failOrSucc eq Succeeded), s"status is not decided but ${failOrSucc}")
        val succeeded = (failOrSucc eq Succeeded)

        @tailrec
        def phase2(entry: MCASEntry): Boolean = entry match {
          case null =>
            succeeded
          case entry =>
            CAS1fromDesc(entry.ref, this, if (succeeded) entry.nv else entry.ov)
            phase2(entry.next)
        }

        val res = phase2(head)
        TlSt.get().saveK(k)
        res
      } finally {
        decr()
      }
    }

    private def RDCSStoDesc[A](
      status: Ref[MCASStatus],
      entry: MCASEntry,
      nv: MCASDesc
    ): RDCSSResult = {
      @tailrec
      def acquire(): RDCSSResult = {
        CAS1toEntry(entry.ref, entry.ov, entry) match {
          case ov if equ(ov, entry.ov) =>
            // ok, we succeeded:
            AcquireSuccess
          case e: MCASEntry =>
            // other op underway, let's help:
            val desc = e.desc
            if (desc ne null) {
              desc.incr()
              if (equ(entry.ref.unsafeTryRead(), e.as[entry.A]) && (e.desc eq desc)) {
                assert(!desc.isLsbSet())
                try {
                  RDCSSComp(desc.status, e, desc)
                } finally {
                  desc.decr()
                }
              } else {
                desc.decr()
              }
            } // else: was released in the meantime
            // retry ours:
            acquire()
          case d: MCASDesc =>
            d
          case _ =>
            // probably other op completed before us:
            AcquireFailure
        }
      }

      val res = acquire()
      res match {
        case AcquireSuccess =>
          RDCSSComp(status, entry, nv)
        case _ =>
          // `acquire` failed, no need
          // to call `RDCSSComp`, it
          // would fail for sure
          ()
      }

      res
    }

    @inline
    private[MCAS] def as[A]: A =
      this.asInstanceOf[A]
  }

  private sealed trait Snapshot extends self.Snap

  private final object EmptySnapshot extends Snapshot {
    override def load(): MCASDesc =
      MCAS.startInternal()
    override def discard(): Unit =
      ()
  }

  private final class MCASEntry extends FreeList[MCASEntry] with Snapshot {

    type A

    // These `var`s doesn't need be volatile, since the
    // only way a thread can get its hands on an entry
    // is either
    // (1) by getting it from the thread-local freelist;
    // (2) or by getting it from a `Ref`, in which case
    //     the volatile read guarantees visibility.

    var ref: Ref[A] = _
    var ov: A = _
    var nv: A = _
    var desc: MCASDesc = _
    override var next: MCASEntry = _

    // TODO: thread-safety
    var snaps: Int = 0

    override def load(): MCASDesc = {
      snaps -= 1
      val desc = MCAS.startInternal()
      desc.withEntries(this)
      desc
    }

    override def discard(): Unit = {
      snaps -= 1
      MCASEntry.releaseUnnededEntries(TlSt.get(), this)
      ()
    }

    private[MCAS] def globalRank: Int =
      ref.##

    @inline
    private[MCAS] def as[X]: X =
      this.asInstanceOf[X]

    @inline
    private[MCAS] def cast[A0]: MCASEntry { type A = A0 } =
      this.asInstanceOf[MCASEntry { type A = A0 }]

    private[MCAS] def copy(tlst: TlSt): MCASEntry = {
      val e = tlst.loanEntry[A]()
      e.ref = this.ref
      e.ov = this.ov
      e.nv = this.nv
      e.desc = this.desc
      e
    }
  }

  private object MCASEntry {

    /**
     * Releases all entries (starting from `head`) which
     * are not needed any more (i.e., they're not part of snapshots).
     */
    @tailrec
    private[MCAS] def releaseUnnededEntries(tlst: TlSt, head: MCASEntry): MCASEntry = {
      if (head eq null) {
        null
      } else {
        if (head.snaps > 0) {
          head
        } else {
          val nxt = head.next
          tlst.releaseEntry(head)
          releaseUnnededEntries(tlst, nxt)
        }
      }
    }
  }

  private final class TlSt {

    import TlSt._

    private[this] val threadId: Long =
      Thread.currentThread().getId

    // These `var`s are thread-local, so
    // there is no need for volatile.

    private[this] var freeEntries: MCASEntry =
      _

    private[this] var numFreeEntries: Int =
      0

    private[this] var weakFreeEntries: WeakReference[MCASEntry] =
      _

    private[this] var freeDescriptors: MCASDesc =
      _

    private[this] var numFreeDescriptors: Int =
      0

    private[this] var weakFreeDescriptors: WeakReference[MCASDesc] =
      _

    private[this] var numAllocs: Long =
      0L

    /** Counter of operations (for reporting statistics) */
    private[this] var numOps: Long =
      Long.MinValue

    /** Size of the biggest k-CAS executed by this thread so far */
    private[this] var maxK: Int =
      0

    def loanEntry[A0](): MCASEntry { type A = A0 } = {
      freeEntries match {
        case null =>
          loanWeakEntry[A0]() match {
            case null =>
              incrAllocs()
              (new MCASEntry).cast[A0]
            case e =>
              e
          }
        case entry =>
          freeEntries = entry.next
          entry.next = null
          decrFreeEntries()
          entry.cast[A0]
      }
    }

    def loanWeakEntry[A0](): MCASEntry { type A = A0 } = {
      loanWeak(weakFreeEntries) match {
        case null => null
        case e => e.cast[A0]
      }
    }

    def releaseEntry(e: MCASEntry): Unit = {
      assert(e.snaps == 0, s"released entry still has ${e.snaps} snapshots")
      e.ref = null
      e.ov = nullOf[e.A]
      e.nv = nullOf[e.A]
      e.desc = null
      if (numFreeEntries >= (maxK * EntryMultiplier)) {
        releaseWeakEntry(e)
      } else {
        e.next = freeEntries
        freeEntries = e
        incrFreeEntries()
      }
    }

    def releaseWeak[A >: Null <: FreeList[A]](a: A, wr: WeakReference[A]): WeakReference[A] = {
      // TODO: maybe don't always allocate new
      wr match {
        case null =>
          a.next = null
          new WeakReference(a)
        case wr =>
          wr.get() match {
            case null =>
              a.next = null
              new WeakReference(a)
            case head =>
              a.next = head.next
              head.next = a
              wr
          }
      }
    }

    def releaseWeakEntry(e: MCASEntry): Unit = {
      weakFreeEntries = releaseWeak(e, weakFreeEntries)
    }

    def loanDescriptor(): MCASDesc = {
      val nxt = freeDescriptors
      val res = if (nxt eq null) {
        loanWeakDescriptor() match {
          case null =>
            incrAllocs()
            new MCASDesc
          case d =>
            d
        }
      } else {
        freeDescriptors = nxt.next
        nxt.next = null
        decrFreeDesc()
        nxt
      }
      res.incr()
      res.clearLsb()
      res
    }

    def loanWeak[A >: Null <: FreeList[A]](wr: WeakReference[A]): A = {
      wr match {
        case null =>
          null
        case wr =>
          wr.get() match {
            case null =>
              null
            case head =>
              head.next match {
                case null =>
                  null
                case tail =>
                  head.next = tail.next
                  tail.next = null
                  tail
              }
          }
      }
    }

    def loanWeakDescriptor(): MCASDesc =
      loanWeak(weakFreeDescriptors)

    def releaseDescriptor(d: MCASDesc): Unit = {
      if (numFreeDescriptors >= DescriptorMultiplier) {
        releaseWeakDescriptor(d)
      } else {
        d.next = freeDescriptors
        freeDescriptors = d
        incrFreeDesc()
      }
      incrOpNum()
    }

    private def releaseWeakDescriptor(d: MCASDesc): Unit = {
      weakFreeDescriptors = releaseWeak(d, weakFreeDescriptors)
    }

    def saveK(k: Int): Unit = {
      if (k > maxK) {
        maxK = k
      }
    }

    private def incrFreeDesc(): Unit = {
      numFreeDescriptors += 1
    }

    private def decrFreeDesc(): Unit = {
      numFreeDescriptors -= 1
    }

    private def incrFreeEntries(): Unit = {
      numFreeEntries += 1
    }

    private def decrFreeEntries(): Unit = {
      numFreeEntries -= 1
    }

    @elidable(LEVEL)
    private def incrOpNum(): Unit = {
      numOps += 1L
      if ((numOps % ReportPeriod) == 0) {
        reportStatistics()
      }
    }

    @elidable(elidable.ASSERTION)
    private def incrAllocs(): Unit = {
      numAllocs += 1L
    }

    @elidable(LEVEL)
    private def reportStatistics(): Unit =
      log(s"maxK = ${maxK}; allocs = ${numAllocs}")

    @elidable(LEVEL)
    private def log(msg: String): Unit =
      System.out.print(s"[Thread ${threadId}] ${msg}\n")
  }

  private final object TlSt {

    /*
     * Ideally a thread would only need
     * 1 descriptor and `maxK` entry.
     * However, (due to helping) retaining
     * (for reuse) more than that can be
     * beneficial. These multipliers control
     * how big we let these freelists grow.
     *
     * After a freelist is full, we're inserting
     * the descriptors/entries to another freelist,
     * which is only held by a weak reference. Thus,
     * this freelist can be freed by the GC any time.
     */

    /** The descriptor freelist is at most this long */
    private final val DescriptorMultiplier = 16

    /** The entry freelist is at most `maxK` * this long */
    private final val EntryMultiplier = 16

    private final val ReportPeriod = 1024 * 1024

    private final val LEVEL = elidable.CONFIG

    private[this] val inst =
      new ThreadLocal[TlSt]()

    def get(): TlSt = {
      inst.get() match {
        case null =>
          val ntl = new TlSt
          inst.set(ntl)
          ntl
        case tl =>
          tl
      }
    }
  }
}
