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
// TODO: do some benchmarks (`val`s may not worth it)

sealed abstract class Counter {

  def add: Rxn[Long, Unit]

  def incr: Axn[Unit]

  def decr: Axn[Unit]

  def count: Axn[Long]
}

object Counter {

  final def simple: Axn[Counter] =
    simple(Ref.AllocationStrategy.Default)

  final def simple(str: Ref.AllocationStrategy): Axn[Counter] =
    Ref(0L, str).map(new SimpleCounter(_))

  final def striped: Axn[Counter] = {
    // default to padded, because for a striped
    // counter it is very likely to be more useful:
    striped(Ref.AllocationStrategy.Padded)
  }

  final def striped(str: Ref.AllocationStrategy): Axn[Counter] = {
    val arrStr = str match {
      case Ref.AllocationStrategy(padded) =>
        // TODO: use flat = true when it supports padding
        Ref.Array.AllocationStrategy(sparse = false, flat = false, padded = padded)
    }
    Axn.unsafe.suspendContext { ctx =>
      Ref.array(size = ctx.stripes, initial = 0L, strategy = arrStr).map { arr =>
        new StripedCounter(arr)
      }
    }
  }

  private[choam] final def simple(initial: Long): Axn[Counter] =
    Ref(initial).map(new SimpleCounter(_))

  private[this] final class SimpleCounter(ref: Ref[Long]) extends Counter {

    final override val add: Rxn[Long, Unit] = ref.update0[Long] { (cnt, n) =>
      cnt + n
    }

    final override val incr: Axn[Unit] =
      add.provide(1L)

    final override val decr: Axn[Unit] =
      add.provide(-1L)

    final override val count: Axn[Long] =
      ref.get
  }

  private[this] final class StripedCounter(arr: Ref.Array[Long]) extends Counter {

    // TODO: Create a reusable "striped ref array"
    // TODO: data type (and use it here).
    // TODO: Look at java.util.concurrent.atomic.Striped64,
    // TODO: which seems similar.

    private[this] final def getStripeId: Axn[Int] =
      Axn.unsafe.delayContext(_.stripeId)

    final override val add: Rxn[Long, Unit] = getStripeId.flatMap { stripeId =>
      val ref = arr.unsafeGet(stripeId)
      ref.update0[Long] { (cnt, n) =>
        cnt + n
      }
    }

    final override val incr: Axn[Unit] =
      add.provide(1L)

    final override val decr: Axn[Unit] =
      add.provide(-1L)

    final override val count: Axn[Long] = {
      val len = arr.length
      def go(idx: Int, acc: Long): Axn[Long] = {
        if (idx >= len) {
          Axn.pure(acc)
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
