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

import cats.syntax.all._

import core.{ =#>, Rxn, Axn, Ref }
import ArrayQueue.{ empty, isEmpty }

/**
 * Array-based circular buffer
 *
 * If it's full, the oldest item
 * will be overwritten by the new
 * incoming item.
 */
private final class RingBuffer[A](
  capacity: Int,
  arr: Ref.Array[A],
  head: Ref[Int], // index for next element to deque
  tail: Ref[Int], // index for next element to enqueue
) extends ArrayQueue[A](capacity, arr, head, tail)
  with Queue.UnsealedWithSize[A] {

  require(capacity === arr.size)

  final override def tryEnqueue: A =#> Boolean =
    this.enqueue.as(true)

  final override def enqueue: Rxn[A, Unit] = Rxn.computed[A, Unit] { newVal =>
    tail.getAndUpdate(incrIdx).flatMapF { idx =>
      arr.unsafeGet(idx).updateWith { oldVal =>
        if (isEmpty(oldVal)) {
          Rxn.pure(newVal)
        } else {
          // we're overwriting the oldest value;
          // we also have to increment the deque index:
          head.update(incrIdx).as(newVal)
        }
      }
    }
  }
}

private object RingBuffer {

  def apply[A](capacity: Int): Axn[RingBuffer[A]] = {
    require(capacity > 0)
    Ref.array[A](size = capacity, initial = empty[A]).flatMapF { arr =>
      makeRingBuffer(capacity, arr)
    }
  }

  // TODO: do we need this?
  private[data] def lazyRingBuffer[A](capacity: Int): Axn[RingBuffer[A]] = {
    require(capacity > 0)
    val str = Ref.Array.AllocationStrategy.Default.withSparse(true)
    Ref.array[A](
      size = capacity,
      initial = empty[A],
      strategy = str,
    ).flatMapF { arr =>
      makeRingBuffer(capacity, arr)
    }
  }

  private[this] def makeRingBuffer[A](capacity: Int, underlying: Ref.Array[A]): Axn[RingBuffer[A]] = {
    require(capacity > 0)
    require(underlying.size === capacity)
    (Ref.padded(0) * Ref.padded(0)).map {
      case (h, t) =>
        new RingBuffer[A](
          capacity = capacity,
          arr = underlying,
          head = h,
          tail = t,
        )
    }
  }
}
