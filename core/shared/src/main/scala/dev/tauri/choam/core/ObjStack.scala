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
package core

private final class ObjStack[A]() {

  private[this] var lst: ObjStack.Lst[A] =
    null

  private[this] var size: Int =
    0

  final override def toString: String = {
    if (this.lst ne null) s"ObjStack(${this.lst.mkString(", ")})"
    else "ObjStack()"
  }

  final def push(a: A, fl: ObjStack.Freelist): Unit = {
    this.lst = fl.tryAlloc[A]() match {
      case null =>
        new ObjStack.Lst(a, this.lst)
      case node =>
        node.head = a
        node.tail = this.lst
        node
    }
    this.size += 1
  }

  final def pushAll(as: Iterable[A], fl: ObjStack.Freelist): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next(), fl)
    }
  }

  private[this] final  def assertNonEmpty(): Unit = {
    if (this.isEmpty) {
      throw new NoSuchElementException
    }
  }

  final def pop(fl: ObjStack.Freelist): A = {
    assertNonEmpty()
    val node = this.lst
    val r = node.head
    this.lst = node.tail
    this.size -= 1
    if (node.refcnt == 0) {
      fl.free(node)
    }
    r
  }

  final def clear(): Unit = {
    this.lst = null
    this.size = 0
  }

  final def isEmpty: Boolean = {
    this.lst eq null
  }

  final def nonEmpty: Boolean = {
    this.lst ne null
  }

  final def takeSnapshot(): ObjStack.Lst[A] = {
    ObjStack.Lst.incrRefcnt(this.lst)
    this.lst
  }

  final def loadSnapshot(snapshot: ObjStack.Lst[A]): Unit = {
    ObjStack.Lst.decrRefcnt(snapshot)
    this.lst = snapshot
    this.size = ObjStack.Lst.length(snapshot)
  }

  final def loadSnapshotUnsafe(snapshot: ObjStack.Lst[Any]): Unit = {
    this.loadSnapshot(snapshot.asInstanceOf[ObjStack.Lst[A]])
  }
}

private object ObjStack {

  abstract class Freelist {
    def tryAlloc[A](): Lst[A]
    def free[A](node: Lst[A]): Unit
  }

  final object Freelist {
    final object Dummy extends Freelist {
      override def tryAlloc[A](): Lst[A] =
        null
      override def free[A](node: Lst[A]): Unit =
        ()
    }
  }

  final class Lst[A](
    final var head: A,
    final var tail: Lst[A],
    final var refcnt: Int = 0,
  ) {

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

    @tailrec
    def incrRefcnt[A](lst: Lst[A]): Unit = {
      if (lst eq null) {
        ()
      } else {
        lst.refcnt += 1
        incrRefcnt(lst.tail)
      }
    }

    @tailrec
    def decrRefcnt[A](lst: Lst[A]): Unit = {
      if (lst eq null) {
        ()
      } else {
        lst.refcnt -= 1
        decrRefcnt(lst.tail)
      }
    }

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
