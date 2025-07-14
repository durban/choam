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

import java.lang.Integer.remainderUnsigned

import cats.syntax.all._

import core.{ Rxn, Ref }
import ArrayQueue.{ empty, isEmpty }

/** Common functionality for array-based queues */
private abstract class ArrayQueue[A](
  val capacity: Int,
  arr: Ref.Array[A],
  head: Ref[Int], // index for next element to deque
  tail: Ref[Int], // index for next element to enqueue
) extends Queue.UnsealedQueuePoll[A]
  with Queue.UnsealedQueueOffer[A] {

  require(capacity === arr.size)

  protected[this] final def incrIdx(idx: Int): Int = {
    remainderUnsigned(idx + 1, capacity)
  }

  final override def poll: Rxn[Option[A]] = {
    head.modifyWith { idx =>
      arr.unsafeGet(idx).modify { a =>
        if (isEmpty(a)) {
          // empty queue
          (a, (idx, None))
        } else {
          // successful deque
          (empty[A], (incrIdx(idx), Some(a)))
        }
      }
    }
  }

  // Note: not final, because `RingBuffer` needs to override it
  override def offer(newVal: A): Rxn[Boolean] = {
    tail.get.flatMap { idx =>
      val ref = arr.unsafeGet(idx)
      ref.get.flatMap { oldVal =>
        if (isEmpty(oldVal)) {
          // ok, we can enqueue:
          ref.set(newVal) *> tail.set(incrIdx(idx)).as(true)
        } else {
          // queue is full:
          Rxn.false_
        }
      }
    }
  }

  def size: Rxn[Int] = {
    (head.get * tail.get).flatMap {
      case (h, t) =>
        if (h < t) {
          Rxn.pure(t - h)
        } else if (h > t) {
          Rxn.pure(t - h + capacity)
        } else { // h == t
          arr.unsafeGet(t).get.map { a =>
            if (isEmpty(a)) 0 // empty
            else capacity // full
          }
        }
    }
  }
}

private object ArrayQueue {

  private[this] object EMPTY {
    def as[A]: A =
      this.asInstanceOf[A]
  }

  private[data] def empty[A]: A =
    EMPTY.as[A]

  private[data] def isEmpty[A](a: A): Boolean =
    equ[A](a, empty[A])
}
