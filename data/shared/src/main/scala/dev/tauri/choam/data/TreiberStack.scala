/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import core.{ Rxn, Ref, Reactive }

private final class TreiberStack[A] private (
  private[this] val head: Ref[TreiberStack.Lst[A]],
) extends Stack.UnsealedStack[A] {

  import TreiberStack._

  final override def push(a: A): Rxn[Unit] = head.update { as =>
    new Cons(a, as)
  }

  final override val poll: Rxn[Option[A]] = head.modify[Option[A]] {
    case cons: Cons[_] => (cons.t, Some(cons.h))
    case End => (End, None)
  }

  final override def peek: Rxn[Option[A]] = head.get.map {
    case cons: Cons[_] => Some(cons.h)
    case End => None
  }

  private[choam] final override val size: Rxn[Int] = // TODO: this is O(n)
    head.get.map(_.length)
}

private object TreiberStack {

  private[data] final def apply[A](str: AllocationStrategy): Rxn[TreiberStack[A]] =
    Ref[Lst[A]](End, str).map(new TreiberStack[A](_))

  private[data] final def fromList[F[_], A](as: List[A], str: AllocationStrategy)(implicit F: Reactive[F]): F[Stack[A]] = {
    Stack.fromList(this.apply[A](str))(as)
  }

  private sealed trait Lst[+A] {

    def length: Int = {
      @tailrec
      def go(l: Lst[A], acc: Int): Int = l match {
        case End => acc
        case cons: Cons[_] => go(cons.t, acc + 1)
      }
      go(this, 0)
    }

    def toList: List[A] = {
      val b = new scala.collection.mutable.ListBuffer[A]
      @tailrec
      def go(l: Lst[A]): Unit = l match {
        case End =>
          ()
        case cons: Cons[_] =>
          b += cons.h
          go(cons.t)
      }
      go(this)
      b.toList
    }
  }

  private final class Cons[A](val h: A, val t: Lst[A]) extends Lst[A]

  private final object End extends Lst[Nothing]
}
