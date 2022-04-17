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

import java.util.Arrays

private final class ObjStack[A]() {

  private[this] var scratch: Array[AnyRef] =
    null

  private[this] var scratchSize: Int =
    0

  private[this] var scratchFrozen: Boolean =
    false

  private[this] var lst: ObjStack.Lst[A] =
    ObjStack.Lst.empty[A]

  newScratch()

  private[this] final def newScratch(): Unit = {
    this.scratch = new Array[AnyRef](ObjStack.nodeSize)
    this.scratchSize = 0
    this.scratchFrozen = false
  }

  final override def toString: String = {
    if (this.lst ne null) {
      if (this.scratchSize > 0) {
        s"ObjStack(${this.scratch.take(this.scratchSize).reverse.mkString(", ")}, ${ObjStack.Lst.mkString(this.lst, sep = ", ")})"
      } else {
        s"ObjStack(${ObjStack.Lst.mkString(this.lst, sep = ", ")})"
      }
    } else {
      if (this.scratchSize > 0) {
        s"ObjStack(${this.scratch.take(this.scratchSize).reverse.mkString(", ")})"
      } else {
        "ObjStack()"
      }
    }
  }

  final def push(a: A): Unit = {
    if (this.scratchFrozen || (this.scratchSize == this.scratch.length)) {
      this.moveScratchToList()
    }
    this.pushToScratch(a)
  }

  private[this] final def pushToScratch(a: A): Unit = {
    require(!this.scratchFrozen)
    this.scratch(this.scratchSize) = a.asInstanceOf[AnyRef]
    this.scratchSize += 1
  }

  private[this] final def moveScratchToList(): Unit = {
    if (this.scratchSize == 0) {
      this.clearOrNewScratch()
    } else if (this.scratchFrozen || (this.scratchSize == ObjStack.nodeSize)) {
      this.lst = ObjStack.Lst.wrapArr(this.scratch, this.scratchSize, this.lst)
      this.newScratch()
    } else {
      this.lst = this.mkListFromScratch()
      this.clearScratch()
    }
  }

  private[this] final def mkListFromScratch(): ObjStack.Lst[A] = {
    val buff = Arrays.copyOfRange(this.scratch, 0, this.scratchSize)
    ObjStack.Lst.wrapArr(buff, buff.length, this.lst)
  }

  private[this] final def clearScratch(): Unit = {
    require(!this.scratchFrozen)
    Arrays.fill(this.scratch, 0, this.scratchSize, null)
    this.scratchSize = 0
  }

  final def pushAll(as: Iterable[A]): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next())
    }
  }

  private[this] final def assertNonEmpty(): Unit = {
    if (this.isEmpty) {
      throw new NoSuchElementException
    }
  }

  final def pop(): A = {
    assertNonEmpty()
    if (this.scratchSize == 0) {
      this.moveFromListToScratch()
    }
    this.popFromScratch()
  }

  private[this] final def popFromScratch(): A = {
    this.scratchSize -= 1
    this.scratch(this.scratchSize).asInstanceOf[A]
  }

  private[this] final def moveFromListToScratch(): Unit = {
    require(this.scratchSize == 0)
    require(this.lst ne null)
    this.scratchSize = this.lst.size
    this.scratch = this.lst.buff
    this.scratchFrozen = true
    this.lst = this.lst.next
  }

  final def clear(): Unit = {
    this.lst = null
    this.clearOrNewScratch()
  }

  private[this] final def clearOrNewScratch(): Unit = {
    if (this.scratchFrozen) {
      this.newScratch()
    } else {
      this.clearScratch()
    }
  }

  final def isEmpty: Boolean = {
    (this.lst eq null) && (this.scratchSize == 0)
  }

  final def nonEmpty: Boolean = {
    (this.lst ne null) || (this.scratchSize > 0)
  }

  final def takeSnapshot(): ObjStack.Lst[A] = {
    if (this.scratchSize > 0) {
      this.scratchFrozen = true
      ObjStack.Lst.wrapArr(this.scratch, this.scratchSize, this.lst)
    } else {
      this.lst
    }
  }

  final def loadSnapshot(snapshot: ObjStack.Lst[A]): Unit = {
    this.lst = snapshot
    this.clearOrNewScratch()
  }

  final def loadSnapshotUnsafe(snapshot: ObjStack.Lst[Any]): Unit = {
    this.lst = snapshot.asInstanceOf[ObjStack.Lst[A]]
    this.clearOrNewScratch()
  }
}

private object ObjStack {

  private final val nodeSize =
    8

  final class Lst[+A] private (
    private[ObjStack] final val buff: Array[AnyRef],
    private[ObjStack] final val size: Int,
    private[ObjStack] final val next: Lst[A],
  ) {

    require(buff.size <= nodeSize)
    require(buff.size > 0)
    require(buff.size >= size)
    require(size > 0)

    final override def toString: String = {
      "ObjStack.Lst(" + this.mkString(", ") + ")"
    }

    private final def mkString(sep: String): String = {
      val sb = new StringBuilder()
      sb.append(this.buff.take(size).reverse.mkString(sep))
      var curr = this.next
      while (curr ne null) {
        sb.append(sep)
        sb.append(curr.buff.take(curr.size).reverse.mkString(sep))
        curr = curr.next
      }
      sb.toString
    }

    private final def splitBufferBefore[AA >: A](item: AA): (Array[AnyRef], Array[AnyRef]) = {
      var idx = size - 1
      var foundIdx = -1
      while ((foundIdx == -1) && (idx >= 0)) {
        if (equ(buff(idx), item)) {
          foundIdx = idx
        } else {
          idx -= 1
        }
      }
      if (foundIdx == -1) {
        null
      } else if (foundIdx == (size - 1)) {
        val before = null
        val rest = Arrays.copyOfRange(buff, 0, size)
        (before, rest)
      } else {
        val rest = new Array[AnyRef](foundIdx + 1)
        System.arraycopy(buff, 0, rest, 0, rest.size)
        val before = new Array[AnyRef](size - rest.size)
        System.arraycopy(buff, rest.size, before, 0, before.size)
        (before, rest)
      }
    }
  }

  final object Lst {

    private[ObjStack] def wrapArr[A](arr: Array[AnyRef], size: Int, next: Lst[A]): Lst[A] =
      new Lst(arr, size, next)

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
      if (lst.size < nodeSize) {
        val buff = new Array[AnyRef](lst.size + 1)
        System.arraycopy(lst.buff, 0, buff, 0, lst.size)
        buff(lst.size) = a.asInstanceOf[AnyRef]
        Lst.wrapArr(buff, buff.size, lst.next)
      } else {
        val buff = new Array[AnyRef](1)
        buff(0) = a.asInstanceOf[AnyRef]
        Lst.wrapArr(buff, 1, lst)
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
              go(rest.next, new Lst(rest.buff, rest.size, acc))
            case (null, after) =>
              (acc, new Lst(after, after.size, rest.next))
            case (before, after) =>
              (new Lst(before, before.size, acc), new Lst(after, after.size, rest.next))
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
        go(lst.next, new Lst(lst.buff, lst.size, acc))
      }
    }
  }
}
