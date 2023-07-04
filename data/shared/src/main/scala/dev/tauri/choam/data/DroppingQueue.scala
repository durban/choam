/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
  with Queue.WithSize[A] {

  final override def tryEnqueue: A =#> Boolean =
    super[ArrayQueue].tryEnqueue

  def enqueue: A =#> Unit =
    this.tryEnqueue.void
}

private object DroppingQueue {

  def apply[A](capacity: Int): Axn[Queue.WithSize[A]] = {
    require(capacity > 0)
    Ref.array[A](size = capacity, initial = empty[A]).flatMapF { arr =>
      (Ref.padded(0) * Ref.padded(0)).map {
        case (h, t) =>
          new DroppingQueue[A](capacity, arr, h, t)
      }
    }
  }
}
