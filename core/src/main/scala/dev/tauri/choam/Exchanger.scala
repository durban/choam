/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ThreadLocalRandom

// TODO: lazy initialization of exchanger with something like Reaction.lzy { ... }

sealed abstract class Exchanger[A] {

  private[this] val array: Array[AtomicReference[Node[A]]] = {
    // TODO: use padded references
    val arr = Array.ofDim[AtomicReference[Node[A]]](Exchanger.size)
    @tailrec
    def go(idx: Int): Unit = {
      if (idx < arr.length) {
        arr(idx) = new AtomicReference[Node[A]]
        go(idx + 1)
      }
    }
    go(0)
    arr
  }

  protected def canExchange(self: A, other: A): Boolean

  private[choam] def tryExchange(a: A): Option[A] = {
    val random = ThreadLocalRandom.current()
    val backoff = 0 // TODO
    val max = 16
    val idx = random.nextInt(backoff + 1)

    // TODO: how to roll back? (Will need to unify the 2 reactions.)
    @tailrec
    def tryIdx(idx: Int, backoff: Int): Option[A] = {
      val slot = this.array(idx)
      slot.get() match {
        case null =>
          // no waiter, insert ourselves:
          val self = new Node(a)
          if (slot.compareAndSet(null, self)) {
            // we have inserted ourselves,
            // now wait for someone to fulfill:
            val res = self.spinWait(backoff, max, random)
            slot.compareAndSet(self, null) // try to clean up
            res
          } else {
            // someone took the slot, retry with
            // another one:
            None
          }
        case other =>
          if (this.canExchange(a, other.value)) {
            // found a matching waiter, try to fulfill it:
            val ok = other.compareAndSet(nullOf[A], a)
            slot.compareAndSet(other, null) // try to clean up
            if (ok) {
              Some(other.value)
            } else {
              // lost the race, someone else fulfilled it, immediately
              // retry the exact same slot (probably it is empty):
              tryIdx(idx, backoff)
            }
          } else {
            // can't match with this waiter, will retry:
            None
          }
      }
    }
    tryIdx(idx, backoff)
  }
}

object Exchanger {

  private[choam] val size = Math.min(
    (Runtime.getRuntime().availableProcessors() + 1) >>> 1,
    0xFF
  )

  /** Private, because an `Exchanger` is unsafe (may block indefinitely) */
  private[choam] def apply[A]: Action[Exchanger[A]] =
    Action.delay { _ => new Exchanger.Simple[A] }

  private final class Simple[A] extends Exchanger[A] {
    protected override def canExchange(self: A, other: A): Boolean =
      true // TODO
  }
}

private final class Node[A](val value: A) extends AtomicReference[A] {

  def spinWait(backoff: Int, max: Int, random: ThreadLocalRandom): Option[A] = {
    @tailrec
    def go(n: Int): Option[A] = {
      if (n > 0) {
        Backoff.once()
        val res = this.get()
        if (isNull(res)) {
          go(n - 1)
        } else {
          Some(res)
        }
      } else {
        None
      }
    }
    go(Backoff.randomTokens(backoff, max, random))
  }
}
