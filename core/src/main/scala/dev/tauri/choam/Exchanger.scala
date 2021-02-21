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

import kcas.{ Ref, ThreadContext, EMCASDescriptor }

// TODO: lazy initialization of exchanger with something like Reaction.lzy { ... }

final class Exchanger[A, B] {

  import Exchanger.{ Node, Msg }

  private[this] val incoming: Array[AtomicReference[Node[A, B, _]]] = {
    // TODO: use padded references
    val arr = Array.ofDim[AtomicReference[Node[A, B, _]]](Exchanger.size)
    Exchanger.initArray(arr)
    arr
  }

  private[this] val outgoing: Array[AtomicReference[Node[B, A, _]]] = {
    // TODO: use padded references
    val arr = Array.ofDim[AtomicReference[Node[B, A, _]]](Exchanger.size)
    Exchanger.initArray(arr)
    arr
  }

  private[choam] def tryExchange[C](msg: Msg[A, B, C], ctx: ThreadContext, retries: Int, maxBackoff: Int = 16): Option[Msg[Unit, Unit, C]] = {
    val idx = Backoff.randomTokens(retries, Exchanger.size >> 1, ctx.random) - 1

    def tryIdx(idx: Int): Option[Msg[Unit, Unit, C]] = {
      // post our message:
      val slot = this.incoming(idx)
      slot.get() match {
        case null =>
          // empty slot, insert ourselves:
          val self = new Node(msg)
          if (slot.compareAndSet(null, self)) {
            // we posted our msg, look at the other side:
            val otherSlot = this.outgoing(idx)
            otherSlot.get() match {
              case null =>
                // we can't fulfill, so we wait for a fulfillment:
                val res = self.spinWait(retries, maxBackoff, ctx)
                slot.compareAndSet(self, null) // try to clean up or rescind
                val rres = if (res.isEmpty) {
                  // re-read, in case it was fulfilled before rescinding
                  // TODO: this is not enough, we still have a race condition
                  // TODO: (we'll probably need a status which means "taken, but not yet fulfilled")
                  ctx.impl.read(self.hole, ctx) match {
                    case null => None
                    case c => Some(c)
                  }
                } else {
                  res
                }
                rres.map { c =>
                  Msg[Unit, Unit, C](
                    value = (),
                    cont = React.ret(c),
                    ops = new React.Reaction(Nil, msg.ops.token),
                    desc = ctx.impl.start(ctx)
                  )
                }
              case other: Node[_, _, d] =>
                // try to clean up:
                // TODO: We should be able to "claim" `other`, otherwise there
                // TODO: is a race with possible other threads fulfilling it
                // TODO: before we could finish fulfilling it.
                // TODO: Similarly, some thread could start fulfilling `msg`,
                // TODO: and that would mean using its mutable `desc`. That's bad.
                slot.compareAndSet(self, null)
                otherSlot.compareAndSet(other, null)
                // we'll fulfill the matching offer:
                val cont: React[Unit, C] = msg.cont.lmap[Unit](_ => other.msg.value)
                val otherCont: React[Unit, Unit] = other.msg.cont.lmap[Unit](_ => msg.value).flatMap { d =>
                  React.cas[d](other.hole, nullOf[d], d)
                }
                val both = (cont * otherCont).map(_._1)
                Some(Msg[Unit, Unit, C](
                  value = (),
                  cont = both,
                  ops = new React.Reaction(
                    msg.ops.postCommit ++ other.msg.ops.postCommit,
                    msg.ops.token // TODO: this might cause problems with `access`
                  ),
                  desc = {
                    // TODO: this only works, if we're the only one using `other` (see above)
                    // Note: we read `other` from a `Ref`, so we'll see its mutable list of descriptors
                    msg.desc.addAll(other.msg.desc)
                    msg.desc
                  }
                ))
            }
          } else {
            // contention, will retry
            None
          }
        case _ =>
          // contention, will retry
          None
      }
    }

    tryIdx(idx)
  }
}

object Exchanger {

  private[choam] final case class Msg[+A, -B, +C](
    value: A,
    cont: React[B, C],
    ops: React.Reaction,
    desc: EMCASDescriptor // TODO: not threadsafe!
    // alts: List[SnapJump[_, B]] // TODO: do we need this?
  )

  private[choam] val size = Math.min(
    (Runtime.getRuntime().availableProcessors() + 1) >>> 1,
    0xFF
  )

  /** Private, because an `Exchanger` is unsafe (may block indefinitely) */
  private[choam] def apply[A, B]: Action[Exchanger[A, B]] =
    Action.delay { _ => new Exchanger[A, B] }

  private def initArray[X, Y](array: Array[AtomicReference[Node[X, Y, _]]]): Unit = {
    @tailrec
    def go(idx: Int): Unit = {
      if (idx < array.length) {
        array(idx) = new AtomicReference[Node[X, Y, _]]
        go(idx + 1)
      }
    }
    go(0)
  }

  private final class Node[A, B, C](val msg: Msg[A, B, C]) {

    val hole = Ref.mk[C](nullOf[C])

    def spinWait(retries: Int, max: Int, ctx: ThreadContext): Option[C] = {
      @tailrec
      def go(n: Int): Option[C] = {
        if (n > 0) {
          Backoff.once()
          val res = ctx.impl.read(this.hole, ctx)
          if (isNull(res)) {
            go(n - 1)
          } else {
            Some(res)
          }
        } else {
          None
        }
      }
      go(Backoff.randomTokens(retries, max, ctx.random))
    }
  }
}
