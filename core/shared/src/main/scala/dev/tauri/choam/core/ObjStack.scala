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

  private[this] var headFrozen: Boolean =
    false

  private[this] var head: ObjStack.Lst[A] =
    ObjStack.Lst.empty[A]

  private[this] final def headSize: Int =
    if (this.head eq null) 0 else this.head.size

  final override def toString: String = {
    s"ObjStack(${ObjStack.Lst.mkString(this.head)})"
  }

  final def push(a: A): Unit = {
    if (this.headFrozen || (this.headSize == ObjStack.nodeSize)) {
      this.moveScratchToList(a)
    } else {
      this.pushToHead(a)
    }
  }

  private[this] final def pushToHead(a: A): Unit = {
    //require(!this.headFrozen)
    if (this.head eq null) {
      this.head = ObjStack.Lst.mk1(a, this.head)
    } else {
      this.head.push(a)
    }
  }

  private[this] final def moveScratchToList(a: A): Unit = {
    this.head = ObjStack.Lst.mk1(a, this.head)
    this.headFrozen = false
  }

  final def pushAll(as: Iterable[A]): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next())
    }
  }

  // private[this] final def assertNonEmpty(): Unit = {
  //   if (this.isEmpty) {
  //     throw new NoSuchElementException
  //   }
  // }

  final def pop(): A = {
    //assertNonEmpty()
    if (this.headSize == 0) {
      this.moveFromListToScratch()
    }
    this.popFromScratch()
  }

  private[this] final def popFromScratch(): A = {
    if (this.headFrozen) {
      this.head = this.head.copyNode()
      this.headFrozen = false
    }
    this.head.pop()
  }

  private[this] final def moveFromListToScratch(): Unit = {
    //require(this.headSize == 0)
    //require(this.head ne null)
    this.head = this.head.next
    this.headFrozen = true
  }

  final def clear(): Unit = {
    if (this.headFrozen) {
      this.head = null
      this.headFrozen = false
    } else if (this.head ne null) {
      this.head.clear()
      this.head.setNext(null)
    }
  }

  final def isEmpty: Boolean = {
    (this.head eq null) || this.head.isEmpty
  }

  final def nonEmpty: Boolean = {
    (this.head ne null) && this.head.nonEmpty
  }

  final def takeSnapshot(): ObjStack.Lst[A] = {
    if (this.head eq null) {
      null
    } else if (this.head.size == 0) {
      this.head.next
    } else {
      this.headFrozen = true
      this.head
    }
  }

  final def loadSnapshot(snapshot: ObjStack.Lst[A]): Unit = {
    this.head = snapshot
    this.headFrozen = true
  }

  final def loadSnapshotUnsafe(snapshot: ObjStack.Lst[Any]): Unit = {
    this.head = snapshot.asInstanceOf[ObjStack.Lst[A]]
    this.headFrozen = true
  }
}

private object ObjStack {

  private final val nodeSize =
    4

  final class Lst[A] private (
    private[ObjStack] final var _0: A,
    private[ObjStack] final var next: Lst[A],
  ) {

    private[ObjStack] final var size: Int =
      1

    private[ObjStack] final var _1: A =
      nullOf[A]

    private[ObjStack] final var _2: A =
      nullOf[A]

    private[ObjStack] final var _3: A =
      nullOf[A]

    final override def toString: String = {
      "ObjStack.Lst(" + this.mkString(", ") + ")"
    }

    private[ObjStack] final def copyNode(): Lst[A] = {
      val res = new Lst[A](this._0, this.next)
      res.size = this.size
      res._1 = this._1
      res._2 = this._2
      res._3 = this._3
      res
    }

    private[ObjStack] final def withNext(newNext: Lst[A]): Lst[A] = {
      val res = this.copyNode()
      res.next = newNext
      res
    }

    private[ObjStack] final def setNext(newNext: Lst[A]): Unit = {
      this.next = newNext
    }

    @tailrec
    final def isEmpty: Boolean = {
      (this.size == 0) && {
        (this.next eq null) || this.next.isEmpty
      }
    }

    @tailrec
    final def nonEmpty: Boolean = {
      (this.size > 0) || {
        (this.next ne null) && this.next.nonEmpty
      }
    }

    private final def mkString(sep: String): String = {
      val sb = new StringBuilder()
      this.mkStringRecursive(sep, sb)
      sb.toString
    }

    @tailrec
    private final def mkStringRecursive(sep: String, sb: StringBuilder): String = {
      if (size > 3) {
        sb.append(this._3)
        sb.append(sep)
      }
      if (size > 2) {
        sb.append(this._2)
        sb.append(sep)
      }
      if (size > 1) {
        sb.append(this._1)
        sb.append(sep)
      }
      if (size > 0) {
        sb.append(this._0)
      }
      if (this.next ne null) {
        sb.append(sep)
        this.next.mkStringRecursive(sep, sb)
      } else {
        sb.result()
      }
    }

    private[ObjStack] final def push(a: A): Unit = {
      require(this.size < nodeSize)
      (this.size : @switch) match {
        case 0 =>
          this._0 = a
        case 1 =>
          this._1 = a
        case 2 =>
          this._2 = a
        case 3 =>
          this._3 = a
      }
      this.size += 1
    }

    private[ObjStack] final def pop(): A = {
      require(this.size > 0)
      val res: A = (this.size : @switch) match {
        case 1 =>
          this._0
        case 2 =>
          this._1
        case 3 =>
          this._2
        case 4 =>
          this._3
      }
      this.size -= 1
      res
    }

    private[ObjStack] final def clear(): Unit = {
      this.size = 0
      this._0 = nullOf[A]
      this._1 = nullOf[A]
      this._2 = nullOf[A]
      this._3 = nullOf[A]
    }

    private final def splitBufferBefore(item: A): (Lst[A], Lst[A]) = {
      (this.size : @switch) match {
        case 4 =>
          if (equ(this._3, item)) {
            (null, this.copyNode())
          } else if (equ(this._2, item)) {
            (Lst.mk1(this._3, null), Lst.mk3(this._0, this._1, this._2, null))
          } else if (equ(this._1, item)) {
            (Lst.mk2(this._2, this._3, null), Lst.mk2(this._0, this._1, null))
          } else if (equ(this._0, item)) {
            (Lst.mk3(this._1, this._2, this._3, null), Lst.mk1(this._0, null))
          } else {
            null
          }
        case 3 =>
          if (equ(this._2, item)) {
            (null, this.copyNode())
          } else if (equ(this._1, item)) {
            (Lst.mk1(this._2, null), Lst.mk2(this._0, this._1, null))
          } else if (equ(this._0, item)) {
            (Lst.mk2(this._1, this._2, null), Lst.mk1(this._0, null))
          } else {
            null
          }
        case 2 =>
          if (equ(this._1, item)) {
            (null, this.copyNode())
          } else if (equ(this._0, item)) {
            (Lst.mk1(this._1, null), Lst.mk1(this._0, null))
          } else {
            null
          }
        case 1 =>
          if (equ(this._0, item)) {
            (null, this.copyNode())
          } else {
            null
          }
        case 0 =>
          (null, this.copyNode())
      }
    }
  }

  final object Lst {

    private[ObjStack] def mk1[A](a: A, next: Lst[A]) = {
      new Lst[A](a, next)
    }

    private[ObjStack] def mk2[A](a0: A, a1: A, next: Lst[A]) = {
      val res = new Lst[A](a0, next)
      res.size = 2
      res._1 = a1
      res
    }

    private[ObjStack] def mk3[A](a0: A, a1: A, a2: A, next: Lst[A]) = {
      val res = new Lst[A](a0, next)
      res.size = 3
      res._1 = a1
      res._2 = a2
      res
    }

    private[ObjStack] def mk4[A](a0: A, a1: A, a2: A, a3: A, next: Lst[A]) = {
      val res = new Lst[A](a0, next)
      res.size = 4
      res._1 = a1
      res._2 = a2
      res._3 = a3
      res
    }

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
        else go(lst.next, acc + lst.size)
      }
      go(lst, acc = 0)
    }

    private def reversedNodes[A](lst: Lst[A]): Lst[A] = {
      go(lst, null)
    }

    def concat[A](x: Lst[A], y: Lst[A]): Lst[A] = {
      val revX = reversedNodes(x)
      go(revX, y)
    }

    def prepend[A](a: A, lst: Lst[A]): Lst[A] = {
      if (lst eq null) {
        Lst.mk1(a, null)
      } else if (lst.size < nodeSize) {
        val res = lst.copyNode()
        res.push(a)
        res
      } else {
        Lst.mk1(a, lst)
      }
    }

    def splitBefore[A](lst: Lst[A], item: A): (Lst[A], Lst[A]) = {
      @tailrec
      def go(rest: Lst[A], acc: Lst[A]): (Lst[A], Lst[A]) = {
        if (rest eq null) {
          null // NB: this is an error the caller must handle
        } else {
          rest.splitBufferBefore(item) match {
            case null =>
              go(rest.next, rest.withNext(acc))
            case (null, after) =>
              after.setNext(rest.next)
              (acc, after)
            case (before, after) =>
              before.setNext(acc)
              after.setNext(rest.next)
              (before, after)
          }
        }
      }
      go(lst, null) match {
        case null =>
          null
        case (init, rest) =>
          (reversedNodes(init), rest)
      }
    }

    @tailrec
    private[this] def go[A](lst: Lst[A], acc: Lst[A]): Lst[A] = {
      if (lst eq null) {
        acc
      } else {
        go(lst.next, lst.withNext(acc))
      }
    }
  }
}
