/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

final class ReferenceCounter {

  private[this] val ref =
    new AtomicReference[Long](0L)

  def count(): Long =
    ref.get()

  def add(n: Long): Long =
    exec[Long](ref, sum(n))

  private[this] def sum(x: Long)(y: Long): Long =
    x + y

  @tailrec
  private[this] def exec[A](ref: AtomicReference[A], f: A => A): A = {
    val old = ref.get()
    if (ref.compareAndSet(old, f(old))) old
    else exec(ref, f)
  }
}
