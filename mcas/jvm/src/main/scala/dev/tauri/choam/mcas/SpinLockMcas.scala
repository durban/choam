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

  final override def currentContext(): Mcas.ThreadContext =
    dummyContext

  private[choam] final override def isThreadSafe =
    true

  private[this] val commitTs: MemoryLocation[Long] =
    MemoryLocation.unsafePadded(Version.Start)

  private[this] val dummyContext = new Mcas.UnsealedThreadContext {

    // NB: it is a `def`, not a `val`
    final override def random =
      ThreadLocalRandom.current()

    final override def tryPerformInternal(desc: HalfEMCASDescriptor): Long = {
      val ops = desc.iterator().toList
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
      val a = ref.unsafeGetVolatile()
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

    final override def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
      @tailrec
      def go(ver1: Long): HalfWordDescriptor[A] = {
        val a = readOne(ref)
        val ver2 = ref.unsafeGetVersionVolatile()
        if (ver1 == ver2) {
          HalfWordDescriptor[A](ref, ov = a, nv = a, version = ver1)
        } else {
          go(ver2)
        }
      }

      go(ref.unsafeGetVersionVolatile())
    }

    protected[mcas] final override def readVersion[A](ref: MemoryLocation[A]): Long = {
      @tailrec
      def go(ver1: Long): Long = {
        val _ = readOne(ref)
        val ver2 = ref.unsafeGetVersionVolatile()
        if (ver1 == ver2) {
          ver1
        } else {
          go(ver2)
        }
      }

      go(ref.unsafeGetVersionVolatile())
    }

    final override def start(): HalfEMCASDescriptor =
      HalfEMCASDescriptor.empty(commitTs, this)

    protected[mcas] final override def addVersionCas(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      desc.addVersionCas(commitTs)

    final override def validateAndTryExtend(
      desc: HalfEMCASDescriptor,
      hwd: HalfWordDescriptor[_],
    ): HalfEMCASDescriptor = {
      desc.validateAndTryExtend(commitTs, this, hwd)
    }

    private def perform(ops: List[HalfWordDescriptor[_]], newVersion: Long): Long = {

      @tailrec
      def lock(ops: List[HalfWordDescriptor[_]]): (List[HalfWordDescriptor[_]], Option[Long]) = ops match {
        case Nil => (Nil, None)
        case h :: tail => h match {
          case head: HalfWordDescriptor[a] =>
            val witness: a = head.address.unsafeCmpxchgVolatile(head.ov, locked[a])
            val isGlobalVerCas = (head.address eq commitTs)
            if (equ(witness, head.ov)) {
              val witnessVer = head.address.unsafeGetVersionVolatile()
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
      def commit(ops: List[HalfWordDescriptor[_]], newVersion: Long): Unit = ops match {
        case Nil => ()
        case h :: tail => h match { case head: HalfWordDescriptor[a] =>
          assert(head.address.unsafeCasVersionVolatile(
            head.address.unsafeGetVersionVolatile(),
            newVersion,
          ))
          head.address.unsafeSetVolatile(head.nv)
          commit(tail, newVersion)
        }
      }

      @tailrec
      def rollback(from: List[HalfWordDescriptor[_]], to: List[HalfWordDescriptor[_]]): Unit = {
        if (from ne to) {
          from match {
            case Nil => impossible("this is the end")
            case h :: tail => h match { case head: HalfWordDescriptor[a] =>
              head.address.unsafeSetVolatile(head.ov)
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
              assert(bv.isEmpty)
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
