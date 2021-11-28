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
package data

import kcas._

final class TreiberStack[A](els: Iterable[A]) {

  import TreiberStack._

  def this() =
    this(Iterable.empty)

  private[this] val head = Ref.unsafe[Lst[A]](End)

  val push: Rxn[A, Unit] = head.upd { (as, a) =>
    (Cons(a, as), ())
  }

  val tryPop: Axn[Option[A]] = head.upd {
    case (Cons(h, t), _) => (t, Some(h))
    case (End, _) => (End, None)
  }

  val unsafePop: Rxn[Any, A] = head.unsafeInvisibleRead.flatMap {
    case c @ Cons(h, t) =>
      head.unsafeCas(c, t).as(h)
    case End =>
      Rxn.unsafe.retry
  }

  val length: Axn[Int] =
    head.upd[Any, Int] { (l, _) => (l, l.length) }

  def toList: Axn[List[A]] =
    head.upd[Any, Lst[A]] { (l, _) => (l, l) }.map(_.toList)

  private[choam] def unsafeToList(kcas: KCAS): List[A] = {
    val r = head.upd[Unit, Lst[A]] { (l, _) => (l, l) }
    r.unsafePerform((), kcas).toList
  }

  els.foreach { a =>
    push.unsafePerform(a, KCAS.NaiveKCAS)
  }
}

object TreiberStack {

  def apply[A]: Axn[TreiberStack[A]] =
    Rxn.unsafe.delay { _ => new TreiberStack[A] }

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
