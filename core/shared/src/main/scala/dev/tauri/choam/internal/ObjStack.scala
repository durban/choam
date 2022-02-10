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
package internal

private[choam] final class ObjStack[A]() {

  private[this] var lst: ObjStack.Lst[A] =
    null

  final override def toString: String = {
    if (this.lst ne null) s"ObjStack(${this.lst.mkString(", ")})"
    else "ObjStack()"
  }

  final def push(a: A): Unit = {
    this.lst = new ObjStack.Lst(a, this.lst)
  }

  final def pushAll(as: Iterable[A]): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next())
    }
  }

  private[this] final  def assertNonEmpty(): Unit = {
    if (this.isEmpty) {
      throw new NoSuchElementException
    }
  }

  final def pop(): A = {
    assertNonEmpty()
    val r = this.lst.head
    this.lst = this.lst.tail
    r
  }

  final def clear(): Unit = {
    this.lst = null
  }

  final def isEmpty: Boolean = {
    this.lst eq null
  }

  final def nonEmpty: Boolean = {
    this.lst ne null
  }

  final def takeSnapshot(): ObjStack.Lst[A] = {
    this.lst
  }

  final def loadSnapshot(snapshot: ObjStack.Lst[A]): Unit = {
    this.lst = snapshot
  }

  final def loadSnapshotUnsafe(snapshot: ObjStack.Lst[Any]): Unit = {
    this.lst = snapshot.asInstanceOf[ObjStack.Lst[A]]
  }
}

private[choam] object ObjStack {

  final class Lst[+A](final val head: A, final val tail: Lst[A]) {

    final def mkString(sep: String = ", "): String = {
      val sb = new StringBuilder()
      sb.append(this.head.toString)
      var curr = this.tail
      while (curr ne null) {
        sb.append(sep)
        sb.append(curr.head.toString)
        curr = curr.tail
      }
      sb.toString
    }
  }

  final object Lst {

    def apply[A](head: A, tail: Lst[A]): Lst[A] =
      new Lst(head, tail)

    def singleton[A](a: A): Lst[A] =
      new Lst(a, null)

    def empty[A]: Lst[A] =
      null

    def mkString[A](lst: Lst[A], sep: String = ", "): String = {
      lst match {
        case null => ""
        case lst => lst.mkString(sep = sep)
      }
    }

    def length[A](lst: Lst[A]): Int = {
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
