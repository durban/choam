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

import core.{ Rxn, Ref }

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
    simple(Ref.AllocationStrategy.Default)

  final def simple(str: Ref.AllocationStrategy): Rxn[Counter] =
    Ref(0L, str).map(new SimpleCounter(_))

  final def striped: Rxn[Counter] = {
    // default to padded, because for a striped
    // counter it is very likely to be more useful:
    striped(Ref.AllocationStrategy.Padded)
  }

  final def striped(str: Ref.AllocationStrategy): Rxn[Counter] = {
    val arrStr = str match {
      case Ref.AllocationStrategy(padded) =>
        // TODO: use flat = true when it supports padding
        Ref.Array.AllocationStrategy(sparse = false, flat = false, padded = padded)
    }
    Rxn.unsafe.suspendContext { ctx =>
      Ref.array(size = ctx.stripes, initial = 0L, strategy = arrStr).map { arr =>
        new StripedCounter(arr)
      }
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

  private[this] final class StripedCounter(arr: Ref.Array[Long]) extends Counter {

    // TODO: Create a reusable "striped ref array"
    // TODO: data type (and use it here).
    // TODO: Look at java.util.concurrent.atomic.Striped64,
    // TODO: which seems similar.

    private[this] final def getStripeId: Rxn[Int] =
      Rxn.unsafe.delayContext(_.stripeId)

    final override def add(n: Long): Rxn[Unit] = getStripeId.flatMap { stripeId =>
      val ref = arr.unsafeGet(stripeId)
      ref.update { cnt => cnt + n }
    }

    final override val incr: Rxn[Unit] =
      add(1L)

    final override val decr: Rxn[Unit] =
      add(-1L)

    final override val count: Rxn[Long] = {
      val len = arr.length
      def go(idx: Int, acc: Long): Rxn[Long] = {
        if (idx >= len) {
          Rxn.pure(acc)
        } else {
          val ref = arr.unsafeGet(idx)
          ref.get.flatMapF { c =>
            go(idx + 1, acc + c)
          }
        }
      }
      go(idx = 0, acc = 0L)
    }
  }
}
