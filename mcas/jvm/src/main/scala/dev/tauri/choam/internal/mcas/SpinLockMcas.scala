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
package internal
package mcas

import java.util.concurrent.ThreadLocalRandom

/**
 * Naïve k-CAS algorithm as described in [Reagents: Expressing and Composing
 * Fine-grained Concurrency](https://web.archive.org/web/20220214132428/https://www.ccis.northeastern.edu/home/turon/reagents.pdf)
 * by Aaron Turon; originally implemented at [aturon/ChemistrySet](
 * https://github.com/aturon/ChemistrySet).
 *
 * While this is logically correct, it basically implements
 * a spinlock for each `Ref`. Thus, it is not lock-free.
 *
 * Implemented as a baseline for benchmarking and correctness tests.
 */
private object SpinLockMcas extends Mcas.UnsealedMcas { self =>

  private[this] val rig: RefIdGen =
    RefIdGen.newGlobal()

  final override def currentContext(): Mcas.ThreadContext =
    dummyContext

  private[choam] final override val osRng: OsRng =
    OsRng.mkNew()

  private[choam] final override def isThreadSafe =
    true

  private[this] val commitTs: MemoryLocation[Long] =
    MemoryLocation.unsafePadded(Version.Start, this.rig)

  private[this] val dummyContext = new Mcas.UnsealedThreadContext {

    final override type START = Descriptor

    final override def impl: Mcas =
      self

    // NB: it is a `def`, not a `val`
    final override def random =
      ThreadLocalRandom.current()

    final override def refIdGen =
      self.rig

    final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long = {
      val ops = desc.hwdIterator.toList
      perform(ops, newVersion = desc.newVersion)
    }

    private[this] final object Locked {
      def as[A]: A =
        this.asInstanceOf[A]
    }

    private[this] def locked[A]: A =
      Locked.as[A]

    private[this] def isLocked[A](a: A): Boolean =
      equ(a, locked[A])

    @tailrec
    private[this] final def readOne[A](ref: MemoryLocation[A]): A = {
      val a = ref.unsafeGetV()
      if (isLocked(a)) {
        Thread.onSpinWait()
        readOne(ref) // retry
      } else {
        a
      }
    }

    final override def readDirect[A](ref: MemoryLocation[A]): A = {
      readOne(ref)
    }

    final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] = {
      @tailrec
      def go(ver1: Long): LogEntry[A] = {
        val a = readOne(ref)
        val ver2 = ref.unsafeGetVersionV()
        if (ver1 == ver2) {
          LogEntry[A](ref, ov = a, nv = a, version = ver1)
        } else {
          go(ver2)
        }
      }

      go(ref.unsafeGetVersionV())
    }

    protected[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long = {
      @tailrec
      def go(ver1: Long): Long = {
        val _ = readOne(ref)
        val ver2 = ref.unsafeGetVersionV()
        if (ver1 == ver2) {
          ver1
        } else {
          go(ver2)
        }
      }

      go(ref.unsafeGetVersionV())
    }

    final override def start(): Descriptor =
      Descriptor.empty(commitTs, this)

    protected[mcas] final override def addVersionCas(desc: AbstractDescriptor): AbstractDescriptor.Aux[desc.D] =
      desc.addVersionCas(commitTs)

    final override def validateAndTryExtend(
      desc: AbstractDescriptor,
      hwd: LogEntry[?],
    ): AbstractDescriptor.Aux[desc.D] = {
      desc.validateAndTryExtend(commitTs, this, hwd)
    }

    private def perform(ops: List[LogEntry[?]], newVersion: Long): Long = {

      @tailrec
      def lock(ops: List[LogEntry[?]]): (List[LogEntry[?]], Option[Long]) = ops match {
        case Nil => (Nil, None)
        case h :: tail => h match {
          case head: LogEntry[a] =>
            val witness: a = head.address.unsafeCmpxchgV(head.ov, locked[a])
            val isGlobalVerCas = (head.address eq commitTs)
            if (equ(witness, head.ov)) {
              val witnessVer = head.address.unsafeGetVersionV()
              if (isGlobalVerCas || (witnessVer == head.version)) {
                lock(tail)
              } else {
                (tail, None) // was locked, need to rollback
              }
            } else {
              val badVersion = if (isGlobalVerCas) {
                if (isLocked(witness)) {
                  None
                } else {
                  val ver = witness.asInstanceOf[Long]
                  Some(ver)
                }
              } else {
                None
              }
              (ops, badVersion) // rollback
            }
        }
      }

      @tailrec
      def commit(ops: List[LogEntry[?]], newVersion: Long): Unit = ops match {
        case Nil => ()
        case h :: tail => h match { case head: LogEntry[_] =>
          val ov = head.address.unsafeGetVersionV()
          val wit = head.address.unsafeCmpxchgVersionV(ov, newVersion)
          _assert(wit == ov)
          head.address.unsafeSetV(head.nv)
          head.address.unsafeNotifyListeners()
          commit(tail, newVersion)
        }
      }

      @tailrec
      def rollback(from: List[LogEntry[?]], to: List[LogEntry[?]]): Unit = {
        if (from ne to) {
          from match {
            case Nil => impossible("this is the end")
            case h :: tail => h match { case head: LogEntry[_] =>
              head.address.unsafeSetV(head.ov)
              rollback(tail, to)
            }
          }
        } else {
          ()
        }
      }

      ops match {
        case Nil =>
          McasStatus.Successful
        case l @ (_ :: _) =>
          lock(l) match {
            case (Nil, bv) =>
              _assert(bv.isEmpty)
              commit(l, newVersion)
              McasStatus.Successful
            case (to @ (_ :: _), bv) =>
              rollback(l, to)
              bv match {
                case Some(ver) => ver
                case None => McasStatus.FailedVal
              }
          }
      }
    }
  }
}
