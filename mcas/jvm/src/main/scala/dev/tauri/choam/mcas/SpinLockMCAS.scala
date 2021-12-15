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

import java.util.concurrent.ThreadLocalRandom

/**
 * Naïve k-CAS algorithm as described in [Reagents: Expressing and Composing
 * Fine-grained Concurrency](https://people.mpi-sws.org/~turon/reagents.pdf)
 * by Aaron Turon; originally implemented at [aturon/ChemistrySet](
 * https://github.com/aturon/ChemistrySet).
 *
 * While this is logically correct, it basically implements
 * a spinlock for each `Ref`. Thus, it is not lock-free.
 *
 * Implemented as a baseline for benchmarking and correctness tests.
 */
private object SpinLockMCAS extends MCAS { self =>

  final override def currentContext(): MCAS.ThreadContext =
    dummyContext

  private[choam] final override def isThreadSafe =
    true

  private[this] val dummyContext = new MCAS.ThreadContext {

    final override def random =
      ThreadLocalRandom.current()

    final override def tryPerform(desc: HalfEMCASDescriptor): Boolean = {
      val ops = desc.map.valuesIterator.toList
      perform(ops)
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
    final override def read[A](ref: MemoryLocation[A]): A = {
      val a = ref.unsafeGetVolatile()
      if (isLocked(a)) {
        Thread.onSpinWait()
        read(ref) // retry
      } else {
        a
      }
    }

    private def perform(ops: List[HalfWordDescriptor[_]]): Boolean = {

      @tailrec
      def lock(ops: List[HalfWordDescriptor[_]]): List[HalfWordDescriptor[_]] = ops match {
        case Nil => Nil
        case h :: tail => h match { case head: HalfWordDescriptor[a] =>
          if (head.address.unsafeCasVolatile(head.ov, locked[a])) lock(tail)
          else ops // rollback
        }
      }

      @tailrec
      def commit(ops: List[HalfWordDescriptor[_]]): Unit = ops match {
        case Nil => ()
        case h :: tail => h match { case head: HalfWordDescriptor[a] =>
          head.address.unsafeSetVolatile(head.nv)
          commit(tail)
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
          true
        case (h : HalfWordDescriptor[a]) :: Nil =>
          h.address.unsafeCasVolatile(h.ov, h.nv)
        case l @ (_ :: _) =>
          lock(l) match {
            case Nil =>
              commit(l)
              true
            case to @ (_ :: _) =>
              rollback(l, to)
              false
          }
      }
    }
  }
}
