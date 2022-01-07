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
package data

private[choam] final class TreiberStack[A] private () extends Stack[A] {

  import TreiberStack._

  private[this] val head = Ref.unsafe[Lst[A]](End)

  final override val push: Rxn[A, Unit] = head.upd { (as, a) =>
    (Cons(a, as), ())
  }

  final override val tryPop: Axn[Option[A]] = head.modify[Option[A]] {
    case Cons(h, t) => (t, Some(h))
    case End => (End, None)
  }

  private[choam] final override val length: Axn[Int] =
    head.get.map(_.length)

  private[choam] def toList: Axn[List[A]] =
    head.get.map(_.toList)

  private[choam] def unsafeToList(kcas: mcas.MCAS): List[A] = {
    this.toList.unsafePerform((), kcas).toList
  }
}

private[choam] object TreiberStack {

  def apply[A]: Axn[TreiberStack[A]] =
    Rxn.unsafe.delay { _ => new TreiberStack[A] }

  def fromList[A](as: List[A]): Axn[TreiberStack[A]] = {
    Rxn.unsafe.context { ctx =>
      val s = new TreiberStack[A]
      as.foreach { a =>
        s.push.unsafePerformInternal(a, ctx = ctx)
      }
      s
    }
  }

  private[choam] sealed trait Lst[+A] {

    def length: Int = {
      @tailrec
      def go(l: Lst[A], acc: Int): Int = l match {
        case End => acc
        case Cons(_, t) => go(t, acc + 1)
      }
      go(this, 0)
    }

    def toList: List[A] = {
      val b = new scala.collection.mutable.ListBuffer[A]
      @tailrec
      def go(l: Lst[A]): Unit = l match {
        case End =>
          ()
        case Cons(h, t) =>
          b += h
          go(t)
      }
      go(this)
      b.toList
    }
  }

  private[choam] final case class Cons[A](h: A, t: Lst[A]) extends Lst[A]

  private[choam] final case object End extends Lst[Nothing]
}
