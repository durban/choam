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
import Exchanger.{ Node, Msg }

// TODO: lazy initialization of exchanger with something like Reaction.lzy { ... }

final class Exchanger[A, B] private (
  incoming: Array[AtomicReference[Node[A, B, _]]],
  outgoing: Array[AtomicReference[Node[B, A, _]]]
) {

  def exchange: React[A, B] =
    React.unsafe.exchange(this)

  // TODO: maybe cache this instance?
  def dual: Exchanger[B, A] =
    new Exchanger[B, A](incoming = this.outgoing, outgoing = this.incoming)

  private[choam] def tryExchange[C](msg: Msg[A, B, C], ctx: ThreadContext, retries: Int, maxBackoff: Int = 16): Option[Msg[Unit, Unit, C]] = {
    val idx = Backoff.randomTokens(retries, Exchanger.size >> 1, ctx.random) - 1

    def tryIdx(idx: Int): Option[Msg[Unit, Unit, C]] = {
      println(s"tryIdx(${idx}) - thread#${Thread.currentThread().getId()}")
      // post our message:
      val slot = this.incoming(idx)
      slot.get() match {
        case null =>
          // empty slot, insert ourselves:
          val self = new Node(msg)
          if (slot.compareAndSet(null, self)) {
            println(s"posted offer - thread#${Thread.currentThread().getId()}")
            // we posted our msg, look at the other side:
            val otherSlot = this.outgoing(idx)
            otherSlot.get() match {
              case null =>
                println(s"not found other, will wait - thread#${Thread.currentThread().getId()}")
                // we can't fulfill, so we wait for a fulfillment:
                val res = self.spinWait(retries, maxBackoff, ctx)
                println(s"after waiting: ${res} - thread#${Thread.currentThread().getId()}")
                if (!slot.compareAndSet(self, null)) {
                  // couldn't rescind, someone claimed our offer
                  println(s"other claimed our offer - thread#${Thread.currentThread().getId()}")
                  val rres = res.orElse {
                    self.spinWait(retries, max = maxBackoff, ctx = ctx)
                  }
                  println(s"rres = ${rres} - thread#${Thread.currentThread().getId()}")
                  rres.map { c =>
                    Msg.ret[C](c, ctx, msg.ops.token)
                  }
                  // TODO: check for claimedAndFailed (???)
                  // TODO: when retrying this reagent, first check `self.hole`!
                } else {
                  // rescinded successfully, will retry
                  None
                }
              case other: Node[_, _, d] =>
                println(s"found other - thread#${Thread.currentThread().getId()}")
                if (slot.compareAndSet(self, null)) {
                  // ok, we've rescinded our offer
                  if (otherSlot.compareAndSet(other, null)) {
                    println(s"fulfilling other - thread#${Thread.currentThread().getId()}")
                    // ok, we've claimed the other offer, we'll fulfill it:
                    val cont: React[Unit, C] = msg.cont.lmap[Unit](_ => other.msg.value)
                    val otherCont: React[Unit, Unit] = other.msg.cont.lmap[Unit](_ => msg.value).flatMap { d =>
                      // TODO: we might not need `onRetry` if we wait/check in React.Exchange`
                      React.unsafe.onRetry(React.unsafe.cas[d](other.hole, nullOf[d], d)) {
                        React.unsafe.cas[d](other.hole, nullOf[d], Exchanger.claimedAndFailed[d])
                      }
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
                        // Note: we've read `other` from a `Ref`, so we'll see its mutable list of descriptors,
                        // and we've claimed it, so others won't try to do the same.
                        msg.desc.addAll(other.msg.desc)
                        msg.desc
                      }
                    ))
                  } else {
                    // the other offer was rescinded in the meantime,
                    // so we'll have to retry:
                    None
                  }
                } else {
                  // someone else claimed our offer, we can't continue with fulfillment,
                  // so we wait for our offer to be fulfilled (and retry if it doesn't happen):
                  self.spinWait(retries, max = maxBackoff, ctx = ctx).map { c =>
                    Msg.ret(c, ctx, msg.ops.token)
                  }
                }
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

  private[this] val _claimedAndFailed =
    new AnyRef

  private[choam] def claimedAndFailed[A]: A =
    _claimedAndFailed.asInstanceOf[A]

  private[choam] def isClaimedAndFailed[A](a: A): Boolean =
    equ(a, claimedAndFailed[A])

  // TODO: this is basically `React.Jump`
  private[choam] final case class Msg[+A, B, +C](
    value: A,
    cont: React[B, C],
    ops: React.Reaction,
    desc: EMCASDescriptor // TODO: not threadsafe!
  )

  private[choam] object Msg {
    def ret[C](c: C, ctx: ThreadContext, tok: React.Token): Msg[Unit, Unit, C] = {
      Msg[Unit, Unit, C](
        value = (),
        cont = React.ret(c),
        ops = new React.Reaction(Nil, tok),
        desc = ctx.impl.start(ctx)
      )
    }
  }

  private[choam] val size = Math.min(
    (Runtime.getRuntime().availableProcessors() + 1) >>> 1,
    0xFF
  )

  /** Private, because an `Exchanger` is unsafe (may block indefinitely) */
  private[choam] def apply[A, B]: Action[Exchanger[A, B]] = Action.delay { _ =>
    val i: Array[AtomicReference[Node[A, B, _]]] = {
      // TODO: use padded references
      val arr = Array.ofDim[AtomicReference[Node[A, B, _]]](Exchanger.size)
      initArray(arr)
      arr
    }
    val o: Array[AtomicReference[Node[B, A, _]]] = {
      // TODO: use padded references
      val arr = Array.ofDim[AtomicReference[Node[B, A, _]]](Exchanger.size)
      initArray(arr)
      arr
    }
    new Exchanger[A, B](i, o)
  }

  private[this] def initArray[X, Y](array: Array[AtomicReference[Node[X, Y, _]]]): Unit = {
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

    /**
     * TODO:
     *
     * null --> result : C (fulfiller successfully completed)
     *  +--> claimedAndFailed (fulfiller couldn't fulfill)
     *  \--> claimedAndRescinded (owner couldn't wait any more for the fulfiller)
     */
    val hole = Ref.unsafe[C](nullOf[C])

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
