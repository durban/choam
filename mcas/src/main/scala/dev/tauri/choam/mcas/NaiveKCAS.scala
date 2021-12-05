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
private[choam] object NaiveKCAS extends KCAS { self =>

  private[this] val dummyGlobal =
    new GlobalContext(self)

  private[this] val dummyContext =
    new ThreadContext(dummyGlobal, 0L, self)

  final override def currentContext(): ThreadContext =
    dummyContext

  final override def tryPerform(desc: HalfEMCASDescriptor, ctx: ThreadContext): Boolean = {
    val ops = desc.map.valuesIterator.toList
    perform(ops)
  }

  @tailrec
  final override def read[A](ref: MemoryLocation[A], ctx: ThreadContext): A = {
    ref.unsafeGetVolatile() match {
      case null =>
        read(ref, ctx)
      case a =>
        a
    }
  }

  private def perform(ops: List[HalfWordDescriptor[_]]): Boolean = {

    @tailrec
    def lock(ops: List[HalfWordDescriptor[_]]): List[HalfWordDescriptor[_]] = ops match {
      case Nil => Nil
      case h :: tail => h match { case head: HalfWordDescriptor[a] =>
        if (head.address.unsafeCasVolatile(head.ov, nullOf[a])) lock(tail)
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
