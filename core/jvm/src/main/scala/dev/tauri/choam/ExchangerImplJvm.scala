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

import mcas.MCAS
import Exchanger.{ Msg, NodeResult, Rescinded, FinishedEx }

// TODO: lazy initialization of exchanger with
// TODO: something like `Rxn.allocateLazily { ... }`
// TODO: (or it could be built-in to the Exchanger)

private final class ExchangerImplJvm[A, B] private (
  d: ExchangerImplJvm[B, A],
) extends Exchanger.UnsealedExchanger[A, B] {

  import ExchangerImplJvm.{ size => _, unsafe => _, _ }

  private val incoming: Array[AtomicReference[ExchangerImplJvm.Node[_]]] = {
    if (d ne null) d.outgoing
    else mkArray()
  }

  private val outgoing: Array[AtomicReference[ExchangerImplJvm.Node[_]]] = {
    if (d ne null) d.incoming
    else mkArray()
  }

  private[this] final val isDebug =
    false

  private[this] final def debugLog(msg: => String): Unit = {
    if (this.isDebug) {
      println(msg)
    }
  }

  final override def exchange: Rxn[A, B] =
    Rxn.internal.exchange[A, B](this)

  final override val dual: ExchangerImplJvm[B, A] = {
    if (d ne null) d
    else new ExchangerImplJvm[B, A](d = this)
  }

  private[this] def size: Int =
    incoming.length

  private[choam] def tryExchange[C](msg: Msg, ctx: MCAS.ThreadContext): Either[StatMap, Msg] = {
    // TODO: the key shouldn't be `this` -- an exchanger and its dual should probably use the same key
    val stats = msg.exchangerData.getOrElse(this, Statistics.zero)
    debugLog(s"tryExchange (effectiveSize = ${stats.effectiveSize}) - thread#${Thread.currentThread().getId()}")
    val idx = if (stats.effectiveSize < 2) 0 else ctx.random.nextInt(stats.effectiveSize.toInt)
    tryIdx(idx, msg, stats, ctx) match {
      case Left(stats) => Left(msg.exchangerData.updated(this, stats))
      case Right(msg) => Right(msg)
    }
  }

  private[this] def tryIdx[C](idx: Int, msg: Msg, stats: Statistics, ctx: MCAS.ThreadContext): Either[Statistics, Msg] = {
    debugLog(s"tryIdx(${idx}) - thread#${Thread.currentThread().getId()}")
    // post our message:
    val slot = this.incoming(idx)
    slot.get() match {
      case null =>
        // empty slot, insert ourselves:
        val self = new Node[C](msg)
        if (slot.compareAndSet(null, self)) {
          debugLog(s"posted offer (contT: ${java.util.Arrays.toString(msg.contT)}) - thread#${Thread.currentThread().getId()}")
          // we posted our msg, look at the other side:
          val otherSlot = this.outgoing(idx)
          otherSlot.get() match {
            case null =>
              debugLog(s"not found other, will wait - thread#${Thread.currentThread().getId()}")
              // we can't fulfill, so we wait for a fulfillment:
              val res: Option[NodeResult[C]] = self.spinWait(stats, ctx)
              debugLog(s"after waiting: ${res} - thread#${Thread.currentThread().getId()}")
              if (!slot.compareAndSet(self, null)) {
                // couldn't rescind, someone claimed our offer
                debugLog(s"other claimed our offer - thread#${Thread.currentThread().getId()}")
                waitForClaimedOffer[C](self, msg, res, stats, ctx)
              } else {
                // rescinded successfully, will retry
                Left(stats.missed)
              }
            case other: Node[d] =>
              debugLog(s"found other - thread#${Thread.currentThread().getId()}")
              if (slot.compareAndSet(self, null)) {
                // ok, we've rescinded our offer
                if (otherSlot.compareAndSet(other, null)) {
                  debugLog(s"fulfilling other - thread#${Thread.currentThread().getId()}")
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
          Left(stats.contended(this.size))
        }
      case _ =>
        // contention, will retry
        Left(stats.contended(this.size))
    }
  }

  /** Our offer have been claimed by somebody, so we wait for them to fulfill it */
  private[this] def waitForClaimedOffer[C](
    self: Node[C],
    msg: Msg,
    maybeResult: Option[NodeResult[C]],
    stats: Statistics,
    ctx: MCAS.ThreadContext
  ): Either[Statistics, Msg] = {
    val rres = maybeResult.orElse {
      self.spinWait(stats = stats, ctx = ctx)
    }
    debugLog(s"waitForClaimedOffer: rres = ${rres} - thread#${Thread.currentThread().getId()}")
    rres match {
      case Some(c) =>
        // it must be the result
        c match {
          case fx: FinishedEx[_] =>
            debugLog(s"waitForClaimedOffer: found result - thread#${Thread.currentThread().getId()}")
            val newStats = msg.exchangerData.updated(this, stats.exchanged)
            Right(Msg.fromFinishedEx(fx, newStats, ctx))
          case _: Rescinded[_] =>
            // we're the only one who can rescind this
            impossible("Someone rescinded our Node!")
        }
      case None =>
        if (ctx.doSingleCas(self.hole.loc, null, Rescinded[C])) {
          // OK, we rolled back, and can retry
          debugLog(s"waitForClaimedOffer: rolled back - thread#${Thread.currentThread().getId()}")
          Left(stats.rescinded)
        } else {
          // couldn't roll back, it must be a result
          ctx.read(self.hole.loc) match {
            case fx: FinishedEx[_] =>
              debugLog(s"waitForClaimedOffer: found result - thread#${Thread.currentThread().getId()}")
              val newStats = msg.exchangerData.updated(this, stats.exchanged)
              Right(Msg.fromFinishedEx(fx, newStats, ctx))
            case _: Rescinded[_] =>
              // we're the only one who can rescind this
              impossible("Someone rescinded our Node!")
          }
        }
    }
  }

  /** We've claimed someone else's offer, so we'll fulfill it */
  private[this] def fulfillClaimedOffer[D](
    other: Node[D],
    selfMsg: Msg,
    stats: Statistics,
    ctx: MCAS.ThreadContext,
  ): Right[Statistics, Msg] = {
    val a: A = selfMsg.value.asInstanceOf[A]
    val b: B = other.msg.value.asInstanceOf[B]
    debugLog(s"fulfillClaimedOffer: selfMsg.value = ${a}; other.msg.value = ${b} - thread#${Thread.currentThread().getId()}")
    val (newContT, newContK) = mergeConts[D](
      selfContT = selfMsg.contT,
      // we put `b` on top of contK; `FinishExchange` will pop it:
      selfContK = ObjStack.Lst[Any](b, selfMsg.contK),
      otherContT = other.msg.contT,
      otherContK = other.msg.contK,
      hole = other.hole,
    )
    val resMsg = Msg(
      value = a,
      contK = newContK,
      contT = newContT,
      // TODO: this must not allow common `Ref`s:
      desc = ctx.addAll(selfMsg.desc, other.msg.desc),
      postCommit = ObjStack.Lst.concat(other.msg.postCommit, selfMsg.postCommit),
      // this thread will continue, so we use (and update) our data:
      exchangerData = selfMsg.exchangerData.updated(this, stats.exchanged)
    )
    debugLog(s"merged postCommit: ${resMsg.postCommit.mkString()} - thread#${Thread.currentThread().getId()}")
    Right(resMsg)
  }

  private[this] def mergeConts[D](
    selfContT: Array[Byte],
    selfContK: ObjStack.Lst[Any],
    otherContT: Array[Byte],
    otherContK: ObjStack.Lst[Any],
    hole: Ref[NodeResult[D]],
  ): (Array[Byte], ObjStack.Lst[Any]) = {
    // otherContK: |-|-|-|-...-|COMMIT|-|-...-|
    //             \-----------/
    // we'll need this first part (until the first commit)
    // and also need an extra op (see below)
    // after this, we'll continue with selfContK
    // (extra op: to fill `other.hole` with the result
    // and also the remaining part of otherContK and otherContT)
    ObjStack.Lst.splitBefore[Any](lst = otherContK, item = Rxn.commitSingleton) match {
      case (prefix, rest) =>
        val extraOp = Rxn.internal.finishExchange[D](
          hole = hole,
          restOtherContK = rest,
          lenSelfContT = selfContT.length,
        )
        val newContK = ObjStack.Lst.concat(
          prefix,
          ObjStack.Lst(extraOp, selfContK),
        )
        val newContT = mergeContTs(selfContT = selfContT, otherContT = otherContT)
        (newContT, newContK)
      case null =>
        val len = ObjStack.Lst.length(otherContK)
        if (len == 0) impossible("empty otherContK")
        else impossible(s"no commit in otherContK: ${otherContK.mkString()}")
    }
  }

  private[this] def mergeContTs(
    selfContT: Array[Byte],
    otherContT: Array[Byte],
  ): Array[Byte] = {
    // The top of the stack has the _highest_ index (see `ByteStack`)
    val res = new Array[Byte](selfContT.length + otherContT.length)
    System.arraycopy(selfContT, 0, res, 0, selfContT.length)
    System.arraycopy(otherContT, 0, res, selfContT.length, otherContT.length)
    res
  }
}

private object ExchangerImplJvm {

  private[choam] type StatMap =
    Map[ExchangerImplJvm[_, _], ExchangerImplJvm.Statistics]

  private[choam] final object StatMap {
    def empty: StatMap =
      Map.empty
  }

  private[choam] final case class Statistics(
    /* Always <= size */
    effectiveSize: Byte,
    /* Counts misses (++) and contention (--) */
    misses: Byte,
    /* How much to wait for an exchange */
    spinShift: Byte,
    /* Counts exchanges (++) and rescinds (--) */
    exchanges: Byte
  ) {

    require(effectiveSize > 0)

    /**
     * Couldn't collide, so we may decrease effective size.
     */
    def missed: Statistics = {
      if (misses == 64.toByte) { // TODO: magic 64
        val newEffSize = Math.max(this.effectiveSize >> 1, 1)
        this.copy(effectiveSize = newEffSize.toByte, misses = 0.toByte)
      } else {
        this.copy(misses = (this.misses + 1).toByte)
      }
    }

    /**
     * An exchange was lost due to 3rd party, so we may
     * increase effective size.
     */
    def contended(size: Int): Statistics = {
      if (misses == (-64).toByte) { // TODO: magic -64
        val newEffSize = Math.min(this.effectiveSize << 1, size)
        this.copy(effectiveSize = newEffSize.toByte, misses = 0.toByte)
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
      Statistics(effectiveSize = 1.toByte, misses = 0.toByte, spinShift = 0.toByte, exchanges = 0.toByte)

    def zero: Statistics =
      _zero

    final val maxSizeShift =
      8 // TODO: magic

    final val maxSpin =
      256 // TODO: magic

    final val defaultSpin =
      256 // TODO: magic; too much
  }

  private[choam] val size = Math.min(
    // `availableProcessors` is guaranteed to return >= 1,
    // so this is always at least (1 + 1) / 2 = 1
    (Runtime.getRuntime().availableProcessors() + 1) >>> 1,
    0xFF
  )

  private[choam] def unsafe[A, B]: ExchangerImplJvm[A, B] = {
    new ExchangerImplJvm[A, B](d = null)
  }

  private def mkArray(): Array[AtomicReference[Node[_]]] = {
    // TODO: use padded references
    // TODO: this really should be an AtomicReferenceArray
    val arr = new Array[AtomicReference[Node[_]]](ExchangerImplJvm.size)
    initArray(arr)
    arr
  }

  private[this] def initArray(array: Array[AtomicReference[Node[_]]]): Unit = {
    @tailrec
    def go(idx: Int): Unit = {
      if (idx < array.length) {
        array(idx) = new AtomicReference[Node[_]]
        go(idx + 1)
      }
    }
    go(0)
  }

  private final class Node[C](val msg: Msg) {

    /**
     *     .---> result: FinishedEx[C] (fulfiller successfully completed)
     *    /
     * null (TODO: use a sentinel)
     *    \
     *     ˙---> Rescinded[C] (owner couldn't wait any more for the fulfiller)
     */
    val hole: Ref[NodeResult[C]] =
      Ref.unsafe(null)

    def spinWait(stats: Statistics, ctx: MCAS.ThreadContext): Option[NodeResult[C]] = {
      @tailrec
      def go(n: Int): Option[NodeResult[C]] = {
        if (n > 0) {
          Backoff.once()
          val res = ctx.read(this.hole.loc)
          if (isNull(res)) {
            go(n - 1)
          } else {
            Some(res)
          }
        } else {
          None
        }
      }
      val maxSpin = Math.min(Statistics.defaultSpin << stats.spinShift.toInt, Statistics.maxSpin)
      val spin = 1 + ctx.random.nextInt(maxSpin)
      // println(s"spin waiting ${spin} (max. ${maxSpin}) - thread#${Thread.currentThread().getId()}")
      go(spin)
    }
  }
}
