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
import ArrayQueue.empty

// TODO: there should be a `BoundedQueue` with `def capacity: Int`

/**
 * Array-based bounded queue
 *
 * If it's full, the incoming
 * item will not be inserted.
 */
private final class DroppingQueue[A](
  capacity: Int,
  arr: Ref.Array[A],
  head: Ref[Int], // index for next element to deque
  tail: Ref[Int], // index for next element to enqueue
) extends ArrayQueue[A](capacity, arr, head, tail)
  with Queue.UnsealedQueueWithSize[A] {

  final override def add(a: A): Rxn[Unit] =
    this.offer(a).void
}

private object DroppingQueue {

  final def apply[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    apply(capacity, Ref.AllocationStrategy.Default)

  final def apply[A](capacity: Int, str: Ref.AllocationStrategy): Rxn[Queue.WithSize[A]] = {
    require(capacity > 0)
    Ref.array[A](size = capacity, initial = empty[A]).flatMap { arr =>
      val pStr = str.withPadded(true)
      (Ref(0, pStr) * Ref(0, pStr)).map {
        case (h, t) =>
          new DroppingQueue[A](capacity, arr, h, t)
      }
    }
  }
}
