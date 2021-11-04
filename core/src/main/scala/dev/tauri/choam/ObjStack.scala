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

  private[this] var head: List[A] =
    Nil

  final override def toString: String = {
    s"ObjStack(${this.head.mkString(", ")})"
  }

  def push(a: A): Unit = {
    this.head = (a :: this.head)
  }

  def pushAll(as: Iterable[A]): Unit = {
    val it = as.iterator
    while (it.hasNext) {
      this.push(it.next())
    }
  }

  private[this] def assertNonEmpty(): Unit = {
    if (this.isEmpty) {
      throw new NoSuchElementException
    }
  }

  def pop(): A = {
    assertNonEmpty()
    val r = this.head.head
    this.head = this.head.tail
    r
  }

  def clear(): Unit = {
    this.head = Nil
  }

  def isEmpty: Boolean = {
    this.head eq Nil
  }

  def nonEmpty: Boolean = {
    !this.isEmpty
  }

  def takeSnapshot(): List[A] = {
    this.head
  }

  def loadSnapshot(snapshot: List[A]): Unit = {
    this.head = snapshot
  }

  def loadSnapshotUnsafe(snapshot: List[Any]): Unit = {
    this.head = snapshot.asInstanceOf[List[A]]
  }
}
