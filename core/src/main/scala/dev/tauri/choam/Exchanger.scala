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

  import Exchanger.{ Node, Statistics }

  require(incoming.length == outgoing.length)

  def exchange: React[A, B] =
    React.unsafe.exchange(this)

  // TODO: maybe cache this instance?
  def dual: Exchanger[B, A] =
    new Exchanger[B, A](incoming = this.outgoing, outgoing = this.incoming)

  private[this] def size: Int =
    incoming.length

  private[choam] def tryExchange[C](msg: Msg[A, B, C], ctx: ThreadContext, retries: Int, maxBackoff: Int = 16): Option[(Msg[Unit, Unit, C])] = {
    val stats = msg.rd.exchangerData.getOrElse(this, Exchanger.Statistics.zero)
    val effectiveSize = this.size >> stats.sizeShift
    val idx = if (effectiveSize < 2) 0 else ctx.random.nextInt(effectiveSize)
    tryIdx(idx, msg, stats, ctx).toOption // TODO
  }

  /** Our offer was claimed by somebody, wait for them to fulfill it */
  private[this] def waitForClaimedOffer[C](
    self: Node[A, B, C],
    msg: Msg[A, B, C],
    maybeResult: Option[C],
    stats: Statistics,
    ctx: ThreadContext
  ): Either[Statistics, Msg[Unit, Unit, C]] = {
    val rres = maybeResult.orElse {
      self.spinWait(stats = stats, ctx = ctx)
    }
    println(s"rres = ${rres} - thread#${Thread.currentThread().getId()}")
    rres match {
      case Some(c) =>
        // it must be a result
        Right(Msg.ret[C](c, ctx, msg.rd.token, msg.rd.exchangerData.updated(this, stats.exchanged)))
      case None =>
        if (ctx.impl.doSingleCas(self.hole, nullOf[C], Exchanger.Node.RESCINDED[C], ctx)) {
          // OK, we rolled back, and can retry
          println(s"rolled back - thread#${Thread.currentThread().getId()}")
          Left(stats.rescinded)
        } else {
          // couldn't roll back, it must be a result
          val c = ctx.impl.read(self.hole, ctx)
          Right(Msg.ret[C](c, ctx, msg.rd.token, msg.rd.exchangerData.updated(this, stats.exchanged)))
        }
    }
  }

  /** We claimed someone else's offer, so we'll fulfill it */
  private[this] def fulfillClaimedOffer[C, D](
    other: Node[B, A, D],
    selfMsg: Msg[A, B, C],
    stats: Statistics,
    ctx: ThreadContext
  ): Right[Statistics, Msg[Unit, Unit, C]] = {
    val cont: React[Unit, C] = selfMsg.cont.lmap[Unit](_ => other.msg.value)
    val otherCont: React[Unit, Unit] = other.msg.cont.lmap[Unit](_ => selfMsg.value).flatMap { d =>
      other.hole.unsafeCas(nullOf[D], d)
    }
    val both = (cont * otherCont).map(_._1)
    val resMsg = Msg[Unit, Unit, C](
      value = (),
      cont = both,
      rd = React.ReactionData(
        selfMsg.rd.postCommit ++ other.msg.rd.postCommit,
        selfMsg.rd.token, // TODO: this might cause problems
        // this thread will continue, so we use (and update) our data
        selfMsg.rd.exchangerData.updated(this, stats.exchanged)
      ),
      desc = {
        // Note: we've read `other` from a `Ref`, so we'll see its mutable list of descriptors,
        // and we've claimed it, so others won't try to do the same.
        selfMsg.desc.addAll(other.msg.desc)
        selfMsg.desc
      }
    )
    //ctx.onRetry.addAll(other.msg.onRetry) // TODO: thread safety?
    Right(resMsg)
  }

  private[this] def tryIdx[C](idx: Int, msg: Msg[A, B, C], stats: Statistics, ctx: ThreadContext): Either[Statistics, Msg[Unit, Unit, C]] = {
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
              val res = self.spinWait(stats, ctx)
              println(s"after waiting: ${res} - thread#${Thread.currentThread().getId()}")
              if (!slot.compareAndSet(self, null)) {
                // couldn't rescind, someone claimed our offer
                println(s"other claimed our offer - thread#${Thread.currentThread().getId()}")
                waitForClaimedOffer[C](self, msg, res, stats, ctx)
              } else {
                // rescinded successfully, will retry
                Left(stats.missed)
              }
            case other: Node[_, _, d] =>
              println(s"found other - thread#${Thread.currentThread().getId()}")
              if (slot.compareAndSet(self, null)) {
                // ok, we've rescinded our offer
                if (otherSlot.compareAndSet(other, null)) {
                  println(s"fulfilling other - thread#${Thread.currentThread().getId()}")
                  // ok, we've claimed the other offer, we'll fulfill it:
                  fulfillClaimedOffer(other, msg, stats, ctx)
                } else {
                  // the other offer was rescinded in the meantime,
                  // so we'll have to retry:
                  Left(stats.rescinded)
                }
              } else {
                // someone else claimed our offer, we can't continue with fulfillment,
                // so we wait for our offer to be fulfilled (and retry if it doesn't happen):
                waitForClaimedOffer(self, msg, None, stats, ctx)
              }
          }
        } else {
          // contention, will retry
          Left(stats.contended)
        }
      case _ =>
        // contention, will retry
        Left(stats.contended)
    }
  }
}

object Exchanger {

  private[choam] type StatMap =
    Map[Exchanger[_, _], Exchanger.Statistics]

  private[choam] final case class Statistics(
    /**
     * `effectiveSize = size >> sizeShift`
     *
     * E.g., for using half of the full size,
     * `sizeShift` should be `1`.
     */
    sizeShift: Byte,
    /** Counts misses (++) and contention (--) */
    misses: Byte,
    /** How much to wait for an exchange */
    spinShift: Byte,
    /** Counts exchanges (++) and rescinds (--) */
    exchanges: Byte
  ) {

    /**
     * Couldn't collide, so we may decrease effective size
     * (i.e., increase `sizeShift`).
     */
    def missed: Statistics = {
      if (misses == 64.toByte) { // TODO: magic 64
        val newShift = Math.min(this.sizeShift + 1, Statistics.maxSizeShift)
        this.copy(sizeShift = newShift.toByte, misses = 0.toByte)
      } else {
        this.copy(misses = (this.misses + 1).toByte)
      }
    }

    /**
     * An exchange was lost due to 3rd party, so we may
     * increase effective size (i.e., decrease `sizeShift`).
     */
    def contended: Statistics = {
      if (misses == (-64).toByte) { // TODO: magic -64
        val newShift = Math.max(this.sizeShift - 1, 0)
        this.copy(sizeShift = newShift.toByte, misses = 0.toByte)
      } else {
        this.copy(misses = (this.misses - 1).toByte)
      }
    }

    def exchanged: Statistics = {
      // TODO: no wait time adaptation implemented for now
      this
    }

    def rescinded: Statistics = {
      // TODO: no wait time adaptation implemented for now
      this
    }
  }

  private[choam] final object Statistics {

    private[this] val _zero: Statistics =
      Statistics(sizeShift = 0.toByte, misses = 0.toByte, spinShift = 0.toByte, exchanges = 0.toByte)

    def zero: Statistics =
      _zero


    final val maxSizeShift =
      8 // TODO: magic

    /** See comment in React#unsafePerform */
    final val maxSpin =
      32

    final val defaultSpin =
      4 // TODO: magic
  }

  // TODO: this is basically `React.Jump`
  private[choam] final case class Msg[+A, B, +C](
    value: A,
    cont: React[B, C],
    rd: React.ReactionData,
    desc: EMCASDescriptor // TODO: not threadsafe!
  )

  private[choam] object Msg {
    def ret[C](c: C, ctx: ThreadContext, tok: React.Token, ed: Exchanger.StatMap): Msg[Unit, Unit, C] = {
      Msg[Unit, Unit, C](
        value = (),
        cont = React.ret(c),
        rd = React.ReactionData(Nil, tok, ed),
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
     *     .---> result: C (fulfiller successfully completed)
     *    /
     * null
     *    \
     *     ˙---> RESCINDED (owner couldn't wait any more for the fulfiller)
     */
    val hole = Ref.unsafe[C](nullOf[C])

    def spinWait(stats: Statistics, ctx: ThreadContext): Option[C] = {
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
      val maxSpin = Math.min(Statistics.defaultSpin << stats.spinShift, Statistics.maxSpin)
      println(s"spin waiting (max. ${maxSpin}) - thread#${Thread.currentThread().getId()}")
      go(ctx.random.nextInt(maxSpin))
    }
  }

  private final object Node {

    private[this] val _RESCINDED: AnyRef =
      new AnyRef { override def toString: String = "_RESCINDED" }

    private[choam] def RESCINDED[A]: A =
      _RESCINDED.asInstanceOf[A]

    private[choam] def IS_RESCINDED[A](a: A): Boolean =
      equ(a, _RESCINDED)
  }
}
