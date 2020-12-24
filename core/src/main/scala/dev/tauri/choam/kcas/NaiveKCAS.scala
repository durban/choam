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
private[kcas] object NaiveKCAS extends KCAS { self =>

  final case class DescRepr(ops: List[CASD[_]]) extends self.Desc with self.Snap {

    override def tryPerform(): Boolean =
      perform(CASD.sortDescriptors(ops))

    override def cancel(): Unit =
      ()

    override def withCAS[A](ref: Ref[A], ov: A, nv: A): self.Desc =
      copy(ops = CASD(ref, ov, nv) :: ops)

    override def snapshot(): self.Snap =
      this

    override def load(): self.Desc =
      this

    override def discard(): Unit =
      ()
  }

  /** CAS descriptor */
  final case class CASD[A](ref: Ref[A], ov: A, nv: A) {

    private[kcas] final def unsafeTryPerformOne(): Boolean =
      ref.unsafeTryPerformCas(ov, nv)

    final override def equals(that: Any): Boolean = that match {
      case CASD(tref, tov, tnv) =>
        (ref eq tref) && equ(ov, tov) && equ(nv, tnv)
      case _ =>
        false
    }

    final override def hashCode: Int =
      ref.## ^ System.identityHashCode(ov) ^ System.identityHashCode(nv)
  }

  final object CASD {

    val refOrdering: Ordering[Ref[_]] = new Ordering[Ref[_]] {
      override def compare(x: Ref[_], y: Ref[_]): Int = {
        Ref.globalCompare(x, y)
      }
    }

    def sortDescriptors(ds: List[CASD[_]]): List[CASD[_]] = {
      val s = new scala.collection.mutable.TreeMap[Ref[_], CASD[_]]()(refOrdering)
      for (d <- ds) {
        s.get(d.ref) match {
          case Some(other) =>
            assert(d.ref eq other.ref)
            KCAS.impossibleKCAS(d.ref, d.ov, d.nv, other.ov, other.nv)
          case None =>
            s.put(d.ref, d)
        }
      }
      s.values.toList
    }
  }

  override def start(): self.Desc =
    DescRepr(Nil)

  override def tryReadOne[A](ref: Ref[A]): A =
    ref.unsafeTryRead()

  private def perform(ops: List[CASD[_]]): Boolean = {

    @tailrec
    def lock(ops: List[CASD[_]]): List[CASD[_]] = ops match {
      case Nil =>
        Nil
      case CASD(ref, ov, _) :: tail =>
        if (ref.unsafeTryPerformCas(ov, null)) lock(tail)
        else ops // rollback
    }

    @tailrec
    def commit(ops: List[CASD[_]]): Unit = ops match {
      case Nil =>
        ()
      case CASD(ref, _, nv) :: tail =>
        ref.unsafeLazySet(nv)
        commit(tail)
    }

    @tailrec
    def rollback(from: List[CASD[_]], to: List[CASD[_]]): Unit = {
      if (from ne to) {
        from match {
          case Nil =>
            impossible("this is the end")
          case CASD(ref, ov, _) :: tail =>
            ref.unsafeLazySet(ov)
            rollback(tail, to)
        }
      } else {
        ()
      }
    }

    ops match {
      case Nil =>
        true
      case h :: Nil =>
        h.unsafeTryPerformOne()
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

  private[choam] final override def isNaive: Boolean =
    true
}
