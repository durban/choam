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
package kcas

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
private[kcas] final object NaiveKCAS extends KCAS { self =>

  private[this] val dummyGlobal =
    new GlobalContext(self)

  private[this] val dummyContext =
    new ThreadContext(dummyGlobal, 0L, self)

  final override def currentContext(): ThreadContext =
    dummyContext

  final override def start(ctx: ThreadContext) =
    new EMCASDescriptor()

  final override def addCas[A](
    desc: EMCASDescriptor,
    ref: Ref[A],
    ov: A,
    nv: A,
    ctx: ThreadContext
  ): EMCASDescriptor = {
    desc.words.add(WordDescriptor[A](ref, ov, nv, desc, dummyContext))
    desc
  }

  final override def snapshot(desc: EMCASDescriptor, ctx: ThreadContext): EMCASDescriptor =
    desc.copy(ctx)

  final override def tryPerform(desc: EMCASDescriptor, ctx: ThreadContext): Boolean = {
    desc.sort()
    val ops = scala.jdk.CollectionConverters.ListHasAsScala(desc.words).asScala.toList
    perform(ops)
  }

  @tailrec
  final override def read[A](ref: Ref[A], ctx: ThreadContext): A = {
    ref.unsafeTryRead() match {
      case null =>
        read(ref, ctx)
      case a =>
        a
    }
  }

  private def perform(ops: List[WordDescriptor[_]]): Boolean = {

    @tailrec
    def lock(ops: List[WordDescriptor[_]]): List[WordDescriptor[_]] = ops match {
      case Nil => Nil
      case h :: tail => h match { case head: WordDescriptor[a] =>
        if (head.address.unsafeTryPerformCas(head.ov, nullOf[a])) lock(tail)
        else ops // rollback
      }
    }

    @tailrec
    def commit(ops: List[WordDescriptor[_]]): Unit = ops match {
      case Nil => ()
      case h :: tail => h match { case head: WordDescriptor[a] =>
        head.address.unsafeLazySet(head.nv)
        commit(tail)
      }
    }

    @tailrec
    def rollback(from: List[WordDescriptor[_]], to: List[WordDescriptor[_]]): Unit = {
      if (from ne to) {
        from match {
          case Nil => impossible("this is the end")
          case h :: tail => h match { case head: WordDescriptor[a] =>
            head.address.unsafeLazySet(head.ov)
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
      case (h : WordDescriptor[a]) :: Nil =>
        h.address.unsafeTryPerformCas(h.ov, h.nv)
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
