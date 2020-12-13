/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.locks.ReentrantLock

final class LockedCounter {

  final class Holder[A](var cnt: A)

  private[this] val h: Holder[Long] =
    new Holder(0L)

  val lck: ReentrantLock =
    new ReentrantLock

  def add(n: Long): Long = {
    lck.lock()
    try {
      unlockedAdd(n)
    } finally {
      lck.unlock()
    }
  }

  def unlockedAdd(n: Long): Long = {
    val old = h.cnt
    h.cnt = old + n
    old
  }

  def count(): Long = this.synchronized {
    lck.lock()
    try {
      unlockedCount()
    } finally {
      lck.unlock()
    }
  }

  def unlockedCount(): Long =
    h.cnt
}

final class LockedCounterN(n: Int) {

  private[this] val ctrs =
    Array.fill(n)(new LockedCounter)

  def add(n: Long): Unit = {
    ctrs.foreach { _.lck.lock() }
    try {
      ctrs.foreach { _.unlockedAdd(n) }
    } finally {
      ctrs.foreach { _.lck.unlock() }
    }
  }
}
