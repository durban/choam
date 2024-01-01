/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package util

import java.util.concurrent.atomic.AtomicReference

final class ReferenceTreiberStack[A](els: Iterable[A]) {

  def this() =
    this(Iterable.empty)

  private[this] val head =
    new AtomicReference[TsList[A]](TsList.End)

  els.foreach(push)

  @tailrec
  def push(a: A): Unit = {
    val curr = head.get()
    if (head.compareAndSet(curr, TsList.Cons(a, curr))) ()
    else push(a)
  }

  @tailrec
  def tryPop(): Option[A] = {
    val curr = head.get()
    curr match {
      case TsList.End =>
        None
      case TsList.Cons(h, t) =>
        if (head.compareAndSet(curr, t)) Some(h)
        else tryPop()
    }
  }

  final def tryPopN(to: Array[A], n: Int): Int = {
    def go(left: Int): Int = {
      this.tryPop() match {
        case None =>
          left
        case Some(a) =>
          to(n - left) = a
          if (left > 1) go(left - 1) else left - 1
      }
    }
    val left = go(n)
    n - left
  }

  final def pushAll(arr: Array[A]): Unit = {
    def go(idx: Int): Unit = {
      if (idx < arr.length) {
        this.push(arr(idx))
        go(idx + 1)
      }
    }
    go(0)
  }

  def length: Int =
    head.get().length
}
