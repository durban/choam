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

import kcas._

final class TreiberStack[A](els: Iterable[A]) {

  import TreiberStack._

  def this() =
    this(Iterable.empty)

  private[this] val head = Ref.mk[Lst[A]](End)

  val push: React[A, Unit] = head.upd { (as, a) =>
    (Cons(a, as), ())
  }

  val tryPop: React[Unit, Option[A]] = head.upd {
    case (Cons(h, t), ()) => (t, Some(h))
    case (End, ()) => (End, None)
  }

  val length: React[Unit, Int] =
    head.upd[Unit, Int] { (l, _) => (l, l.length) }

  private[choam] def unsafeToList()(implicit kcas: KCAS): List[A] = {
    val r = head.upd[Unit, Lst[A]] { (l, _) => (l, l) }
    r.unsafeRun().toList
  }

  els.foreach { a =>
    push.unsafePerform(a)(KCAS.NaiveKCAS)
  }
}

private[choam] object TreiberStack {

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
