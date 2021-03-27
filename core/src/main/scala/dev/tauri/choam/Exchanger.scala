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

  private[choam] def tryExchange[C](msg: Msg[A, B, C], ctx: ThreadContext, retries: Int, maxBackoff: Int = 16): Option[(Msg[Unit, Unit, C])] = {
    val idx = Backoff.randomTokens(retries, Exchanger.size >> 1, ctx.random) - 1
    tryIdx(idx, msg, ctx, retries, maxBackoff)
  }

  /** Our offer was claimed by somebody, wait for them to fulfill it */
  private[this] def waitForClaimedOffer[C](
    self: Node[A, B, C],
    msg: Msg[A, B, C],
    maybeResult: Option[C],
    ctx: ThreadContext,
    retries: Int,
    maxBackoff: Int
  ): Option[Msg[Unit, Unit, C]] = {
    val rres = maybeResult.orElse {
      self.spinWait(retries, max = maxBackoff, ctx = ctx)
    }
    println(s"rres = ${rres} - thread#${Thread.currentThread().getId()}")
    rres match {
      case Some(c) =>
        if (Exchanger.Node.IS_FAILED(c)) {
          println(s"fulfiller FAILED 1 - thread#${Thread.currentThread().getId()}")
          None // fulfiller failed, must retry
        } else {
          // it must be a result
          Some(Msg.ret[C](c, ctx, msg.ops.token))
        }
      case None =>
        if (ctx.impl.doSingleCas(self.hole, nullOf[C], Exchanger.Node.RESCINDED[C], ctx)) {
          // OK, we rolled back, and can retry
          println(s"rolled back - thread#${Thread.currentThread().getId()}")
          None
        } else {
          // couldn't roll back, re-read
          val maybeResult = ctx.impl.read(self.hole, ctx)
          if (Exchanger.Node.IS_FAILED(maybeResult)) {
            // fulfiller failed, we can retry
            println(s"fulfiller FAILED 2 - thread#${Thread.currentThread().getId()}")
            None
          } else {
            // now it must be a result
            Some(Msg.ret[C](maybeResult, ctx, msg.ops.token))
          }
        }
    }
  }

  /** We claimed someone else's offer, so we'll fulfill it */
  private[this] def fulfillClaimedOffer[C, D](
    other: Node[B, A, D],
    selfMsg: Msg[A, B, C]
  ): Option[Msg[Unit, Unit, C]] = {
    val cont: React[Unit, C] = selfMsg.cont.lmap[Unit](_ => other.msg.value)
    val otherCont: React[Unit, Unit] = other.msg.cont.lmap[Unit](_ => selfMsg.value).flatMap { d =>
      React.unsafe.onRetry(other.hole.unsafeCas(nullOf[D], d)) {
        other.hole.unsafeCas(nullOf[D], Exchanger.Node.FAILED[D]).?.void
      }
    }
    val both = (cont * otherCont).map(_._1)
    val resMsg = Msg[Unit, Unit, C](
      value = (),
      cont = both,
      ops = new React.Reaction(
        selfMsg.ops.postCommit ++ other.msg.ops.postCommit,
        selfMsg.ops.token // TODO: this might cause problems
      ),
      desc = {
        // Note: we've read `other` from a `Ref`, so we'll see its mutable list of descriptors,
        // and we've claimed it, so others won't try to do the same.
        selfMsg.desc.addAll(other.msg.desc)
        selfMsg.desc
      }
    )
    Some(resMsg)
  }

  private[this] def tryIdx[C](idx: Int, msg: Msg[A, B, C], ctx: ThreadContext, retries: Int, maxBackoff: Int): Option[Msg[Unit, Unit, C]] = {
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
                waitForClaimedOffer[C](self, msg, res, ctx, retries, maxBackoff)
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
                  fulfillClaimedOffer(other, msg)
                } else {
                  // the other offer was rescinded in the meantime,
                  // so we'll have to retry:
                  None
                }
              } else {
                // someone else claimed our offer, we can't continue with fulfillment,
                // so we wait for our offer to be fulfilled (and retry if it doesn't happen):
                waitForClaimedOffer(self, msg, None, ctx, retries, maxBackoff)
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
}

object Exchanger {

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
     * null --> result: C (fulfiller successfully completed)
     *  +--> FAILED (fulfiller couldn't fulfill)
     *  \--> RESCINDED (owner couldn't wait any more for the fulfiller)
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

  private final object Node {

    private[this] val _FAILED =
      new AnyRef

    private[choam] def FAILED[A]: A =
      _FAILED.asInstanceOf[A]

    private[choam] def IS_FAILED[A](a: A): Boolean =
      equ(a, _FAILED)

    private[this] val _RESCINDED =
      new AnyRef

    private[choam] def RESCINDED[A]: A =
      _RESCINDED.asInstanceOf[A]

    private[choam] def IS_RESCINDED[A](a: A): Boolean =
      equ(a, _RESCINDED)
  }
}
