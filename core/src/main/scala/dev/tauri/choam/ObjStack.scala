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

private final class ObjStack[A]() {

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

private object ObjStack {

  final class Lst[+A](final val head: A, final val tail: Lst[A]) {

    final def mkString(sep: String): String = {
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
}
