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

final class LockedStack[A](els: Iterable[A]) {

  def this() =
    this(Iterable.empty)

  private[this] var head: TsList[A] =
    TsList.End

  val lock =
    new java.util.concurrent.locks.ReentrantLock

  lock.lock()
  try {
    els.foreach(unlockedPush)
  } finally {
    lock.unlock()
  }

  def push(a: A): Unit = {
    lock.lock()
    try {
      unlockedPush(a)
    } finally {
      lock.unlock()
    }
  }

  def unlockedPush(a: A): Unit = {
    head = TsList.Cons(a, head)
  }

  def tryPop(): Option[A] = {
    lock.lock()
    try {
      unlockedTryPop()
    } finally {
      lock.unlock()
    }
  }

  def unlockedTryPop(): Option[A] = {
    head match {
      case TsList.Cons(h, t) =>
        head = t
        Some(h)
      case TsList.End =>
        None
    }
  }

  def length: Int = this.synchronized {
    head.length
  }
}
