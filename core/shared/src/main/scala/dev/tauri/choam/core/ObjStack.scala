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
package core

import java.lang.StringBuilder

private abstract class ObjStack[A]() {

  def push(a: A): Unit

  def push2(a1: A, a2: A): Unit

  def push3(a1: A, a2: A, a3: A): Unit

  def pop(): A

  def peek(): A

  def peekSecond(): A

  def clear(): Unit

  def isEmpty(): Boolean

  def nonEmpty(): Boolean

  def takeAnySnapshot(): ObjStack.Snapshot[A]

  def loadAnySnapshot(snap: ObjStack.Snapshot[A]): Unit

  @tailrec
  final def popAndDiscard(n: Int): Unit = { // TODO: optimize
    if (n > 0) {
      this.pop() : Unit
      this.popAndDiscard(n - 1)
    } else {
      ()
    }
  }

  final def pushAll(as: Iterable[A]): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next())
    }
  }
}

private object ObjStack {

  sealed abstract class Snapshot[+A] {
    def toLst: Lst[A]
  }

  final class Arr[+A](val arr: Array[AnyRef]) extends Snapshot[A] {

    final override def toLst: Lst[A] = {
      val arr = this.arr
      unsafeArrToLst(arr, arr.length)
    }
  }

  final def unsafeArrToLst[A](arr: Array[AnyRef], len: Int): Lst[A] = {
    jsCheckIdx(len - 1, arr.length)
    var lst = Lst.empty[A]
    var idx = 0
    while (idx < len) {
      lst = Lst(head = arr(idx).asInstanceOf[A], tail = lst)
      idx += 1
    }
    lst
  }

  final class Lst[+A](final val head: A, final val tail: Lst[A]) extends Snapshot[A] {

    final override def toLst: Lst[A] =
      this

    final def mkString(sep: String = ", "): String = {
      val sb = new StringBuilder()
      sb.append(this.head.toString)
      var curr = this.tail
      while (curr ne null) {
        sb.append(sep)
        sb.append(curr.head.toString)
        curr = curr.tail
      }
      sb.toString()
    }

    final override def equals(that: Any): Boolean = {
      that match {
        case lst: Lst[_] =>
          this.equalsTo(lst)
        case _ =>
          false
      }
    }

    private[this] final def equalsTo(that: Lst[_]): Boolean = {
      Lst.isEqual(this, that)
    }
  }

  final object Lst {

    final def apply[A](head: A, tail: Lst[A]): Lst[A] =
      new Lst(head, tail)

    final def singleton[A](a: A): Lst[A] =
      new Lst(a, null)

    final def empty[A]: Lst[A] =
      null

    final def isEmpty[A](lst: Lst[A]): Boolean =
      lst eq null

    final def build[A](as: A*): Lst[A] = {
      val itr = as.reverseIterator
      var lst: Lst[A] = empty[A]
      while (itr.hasNext) {
        lst = Lst(itr.next(), lst)
      }
      lst
    }

    @tailrec
    private final def isEqual(x: Lst[_], y: Lst[_]): Boolean = {
      x match {
        case null =>
          y eq null
        case x =>
          (y ne null) && (x.head == y.head) && isEqual(x.tail, y.tail)
      }
    }

    final def mkString[A](lst: Lst[A], sep: String = ", "): String = {
      lst match {
        case null => ""
        case lst => lst.mkString(sep = sep)
      }
    }

    final def length[A](lst: Lst[A]): Int = {
      @tailrec
      def go(lst: Lst[A], acc: Int): Int = {
        if (lst eq null) acc
        else go(lst.tail, acc + 1)
      }
      go(lst, acc = 0)
    }

    def reversed[A](lst: Lst[A]): Lst[A] = {
      go(lst, null)
    }

    def concat[A](x: Lst[A], y: Lst[A]): Lst[A] = {
      val revX = reversed(x)
      go(revX, y)
    }

    def splitBefore[A](lst: Lst[A], item: A): (Lst[A], Lst[A]) = {
      @tailrec
      def go(rest: Lst[A], acc: Lst[A]): (Lst[A], Lst[A]) = {
        if (rest eq null) {
          null // NB: this is an error the caller must handle
        } else if (equ(rest.head, item)) {
          (acc, rest)
        } else {
          go(rest.tail, Lst(rest.head, acc))
        }
      }
      go(lst, null) match {
        case null =>
          null
        case (init, rest) =>
          (reversed(init), rest)
      }
    }

    @tailrec
    private[this] def go[A](lst: Lst[A], acc: Lst[A]): Lst[A] = {
      if (lst eq null) {
        acc
      } else {
        go(lst.tail, new Lst(lst.head, acc))
      }
    }
  }
}
