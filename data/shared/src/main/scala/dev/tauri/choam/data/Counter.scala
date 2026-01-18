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

import core.{ Rxn, Ref, StripedRef }

// TODO: elimination counter (what do with different add values?)
// TODO: do some benchmarks (`val`s may not worth it)

sealed abstract class Counter {

  def add(n: Long): Rxn[Unit]

  def incr: Rxn[Unit]

  def decr: Rxn[Unit]

  def count: Rxn[Long]
}

object Counter {

  final def simple: Rxn[Counter] =
    simple(AllocationStrategy.Default)

  final def simple(str: AllocationStrategy): Rxn[Counter] =
    Ref(0L, str).map(new SimpleCounter(_))

  final def striped: Rxn[Counter] = {
    // default to padded, because for a striped
    // counter it is very likely to be more useful:
    striped(AllocationStrategy.Padded)
  }

  final def striped(str: AllocationStrategy): Rxn[Counter] = {
    Rxn.unsafe.delayContext { ctx =>
      val sref = StripedRef.unsafe(0L, str, ctx)
      new StripedCounter(sref)
    }
  }

  private[choam] final def simple(initial: Long): Rxn[Counter] =
    Ref(initial).map(new SimpleCounter(_))

  private[this] final class SimpleCounter(ref: Ref[Long]) extends Counter {

    final override def add(n: Long): Rxn[Unit] =
      ref.update { cnt => cnt + n }

    final override val incr: Rxn[Unit] =
      add(1L)

    final override val decr: Rxn[Unit] =
      add(-1L)

    final override val count: Rxn[Long] =
      ref.get
  }

  private[this] final class StripedCounter(sref: StripedRef[Long]) extends Counter {

    final override def add(n: Long): Rxn[Unit] = {
      sref.current.update { cnt => cnt + n }
    }

    final override val incr: Rxn[Unit] =
      add(1L)

    final override val decr: Rxn[Unit] =
      add(-1L)

    final override val count: Rxn[Long] = {
      sref.fold(Rxn.pure(0L)) { (acc, ref) =>
        acc.flatMap { count => ref.get.map(c => count + c) }
      }
    }
  }
}
