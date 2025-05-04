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
package data

// TODO: elimination counter (what do with different add values?)
// TODO: something like LongAdder (no atomic `get`, but fast)
// TODO: do some benchmarks (`val`s may not worth it)

sealed abstract class Counter {

  def add: Rxn[Long, Long]

  def incr: Axn[Long]

  def decr: Axn[Long]

  def count: Axn[Long]
}

object Counter {

  final def simple: Axn[Counter] =
    simple(Ref.AllocationStrategy.Default)

  final def simple(str: Ref.AllocationStrategy): Axn[Counter] =
    Ref(0L, str).map(new SimpleCounter(_))

  private[choam] final def simple(initial: Long): Axn[Counter] =
    Ref(initial).map(new SimpleCounter(_))

  private[this] final class SimpleCounter(ref: Ref[Long]) extends Counter {

    final override val add: Rxn[Long, Long] = ref.upd[Long, Long] { (cnt, n) =>
      (cnt + n, cnt)
    }

    final override val incr: Axn[Long] =
      add.provide(1L)

    final override val decr: Axn[Long] =
      add.provide(-1L)

    final override val count: Axn[Long] =
      ref.get
  }
}
