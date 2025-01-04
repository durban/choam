/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

final class LockedQueue[A](els: Iterable[A]) {

  private[this] val q =
    new java.util.ArrayDeque[A]

  val lock =
    new java.util.concurrent.locks.ReentrantLock

  els.foreach(enqueue)

  def enqueue(a: A): Unit = {
    lock.lock()
    try {
      unlockedEnqueue(a)
    } finally {
      lock.unlock()
    }
  }

  def unlockedEnqueue(a: A): Unit = {
    q.offer(a)
    ()
  }

  def tryDequeue(): Option[A] = {
    lock.lock()
    try {
      unlockedTryDequeue()
    } finally {
      lock.unlock()
    }
  }

  def unlockedTryDequeue(): Option[A] = {
    Option(q.poll())
  }
}
