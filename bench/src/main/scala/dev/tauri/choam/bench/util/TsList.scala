/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

/** Thread-safe list */
private[choam] sealed abstract class TsList[+A] {

  import TsList._

  def length: Int = {
    @tailrec
    def go(l: TsList[A], acc: Int): Int = l match {
      case End => acc
      case Cons(_, t) => go(t, acc + 1)
    }
    go(this, 0)
  }

  def toList: List[A] = {
    val b = new scala.collection.mutable.ListBuffer[A]
    @tailrec
    def go(l: TsList[A]): Unit = l match {
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

private[choam] object TsList {
  final case class Cons[A](h: A, t: TsList[A]) extends TsList[A]
  final case object End extends TsList[Nothing]
}
