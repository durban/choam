/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicReferenceArray

import mcas.MCAS
import Exchanger.{ Msg, NodeResult, Rescinded, FinishedEx, Params }

// TODO: move all exchanger stuff into dev.tauri.choam.exchanger
private sealed trait ExchangerImplJvm[A, B]
  extends Exchanger.UnsealedExchanger[A, B] {

  import ExchangerImplJvm.{ size => _, _ }

  // TODO: could we use a single elimination array?
  protected def incoming: AtomicReferenceArray[ExchangerNode[_]]

  protected def outgoing: AtomicReferenceArray[ExchangerNode[_]]

  private[choam] def key: Exchanger.Key

  protected def initializeIfNeeded(retInc: Boolean): AtomicReferenceArray[ExchangerNode[_]]

  final override def exchange: Rxn[A, B] =
    Rxn.internal.exchange[A, B](this)

  private[this] final val isDebug =
    false

  private[this] final def debugLog(msg: => String): Unit = {
    if (this.isDebug) {
      println(msg)
    }
  }

  private[choam] final def tryExchange[C](
    msg: Msg,
    params: Params,
    ctx: MCAS.ThreadContext,
  ): Either[StatMap, Msg] = {
    // TODO: exchangerData grows forever
    val stats = msg.exchangerData.getOrElse(this.key, Statistics.zero)
    debugLog(s"tryExchange (effectiveSize = ${stats.effectiveSize}) - thread#${Thread.currentThread().getId()}")
    val idx = if (stats.effectiveSize < 2) 0 else ctx.random.nextInt(stats.effectiveSize.toInt)
    tryIdx(idx, msg, stats, params, ctx) match {
      case Left(stats) => Left(msg.exchangerData.updated(this.key, stats))
      case Right(msg) => Right(msg)
    }
  }

  private[this] final def tryIdx[C](idx: Int, msg: Msg, stats: Statistics, params: Params, ctx: MCAS.ThreadContext): Either[Statistics, Msg] = {
    debugLog(s"tryIdx(${idx}) - thread#${Thread.currentThread().getId()}")
    val incoming = this.incoming match {
      case null =>
        this.initializeIfNeeded(retInc = true)
      case array =>
        array
    }
    assert(incoming ne null)
    // post our message:
    incoming.get(idx) match {
      case null =>
        // empty slot, insert ourselves:
        val self = new ExchangerNode[C](msg)
        if (incoming.compareAndSet(idx, null, self)) {
          debugLog(s"posted offer (contT: ${java.util.Arrays.toString(msg.contT)}) - thread#${Thread.currentThread().getId()}")
          // we posted our msg, look at the other side:
          val outgoing = this.outgoing match {
            case null =>
              this.initializeIfNeeded(retInc = false)
            case array =>
              array
          }
          outgoing.get(idx) match {
            case null =>
              debugLog(s"not found other, will wait - thread#${Thread.currentThread().getId()}")
              // we can't fulfill, so we wait for a fulfillment:
              val res: Option[NodeResult[C]] = self.spinWait(stats, params, ctx)
              debugLog(s"after waiting: ${res} - thread#${Thread.currentThread().getId()}")
              if (!incoming.compareAndSet(idx, self, null)) {
                // couldn't rescind, someone claimed our offer
                debugLog(s"other claimed our offer - thread#${Thread.currentThread().getId()}")
                waitForClaimedOffer[C](self, msg, res, stats, params, ctx)
              } else {
                // rescinded successfully, will retry
                Left(stats.missed(params).rescinded(params))
              }
            case other: ExchangerNode[d] =>
              debugLog(s"found other - thread#${Thread.currentThread().getId()}")
              if (incoming.compareAndSet(idx, self, null)) {
                // ok, we've rescinded our offer
                if (outgoing.compareAndSet(idx, other, null)) {
                  debugLog(s"fulfilling other - thread#${Thread.currentThread().getId()}")
                  // ok, we've claimed the other offer, we'll fulfill it:
                  fulfillClaimedOffer(other, msg, stats, params, ctx)
                } else {
                  // the other offer was rescinded in the meantime,
                  // so we'll have to retry:
                  Left(stats.rescinded(params))
                }
              } else {
                // someone else claimed our offer, we can't continue with fulfillment,
                // so we wait for our offer to be fulfilled (and retry if it doesn't happen):
                waitForClaimedOffer(self, msg, None, stats, params, ctx)
              }
          }
        } else {
          // contention, will retry
          Left(stats.contended(incoming.length(), params))
        }
      case _ =>
        // contention, will retry
        Left(stats.contended(incoming.length(), params))
    }
  }

  /** Our offer have been claimed by somebody, so we wait for them to fulfill it */
  private[this] final def waitForClaimedOffer[C](
    self: ExchangerNode[C],
    msg: Msg,
    maybeResult: Option[NodeResult[C]],
    stats: Statistics,
    params: Params,
    ctx: MCAS.ThreadContext,
  ): Either[Statistics, Msg] = {
    val rres = maybeResult.orElse {
      self.spinWait(stats = stats, params = params, ctx = ctx)
    }
    debugLog(s"waitForClaimedOffer: rres = ${rres} - thread#${Thread.currentThread().getId()}")
    rres match {
      case Some(c) =>
        // it must be the result
        c match {
          case fx: FinishedEx[_] =>
            debugLog(s"waitForClaimedOffer: found result - thread#${Thread.currentThread().getId()}")
            val newStats = msg.exchangerData.updated(this.key, stats.exchanged(params))
            Right(Msg.fromFinishedEx(fx, newStats, ctx))
          case _: Rescinded[_] =>
            // we're the only one who can rescind this
            impossible("Someone rescinded our Node!")
        }
      case None =>
        if (ctx.tryPerformSingleCas(self.hole.loc, null, Rescinded[C])) {
          // OK, we rolled back, and can retry
          debugLog(s"waitForClaimedOffer: rolled back - thread#${Thread.currentThread().getId()}")
          Left(stats.rescinded(params))
        } else {
          // couldn't roll back, it must be a result
          ctx.read(self.hole.loc) match {
            case fx: FinishedEx[_] =>
              debugLog(s"waitForClaimedOffer: found result - thread#${Thread.currentThread().getId()}")
              val newStats = msg.exchangerData.updated(this.key, stats.exchanged(params))
              Right(Msg.fromFinishedEx(fx, newStats, ctx))
            case _: Rescinded[_] =>
              // we're the only one who can rescind this
              impossible("Someone rescinded our Node!")
          }
        }
    }
  }

  /** We've claimed someone else's offer, so we'll fulfill it */
  private[this] final def fulfillClaimedOffer[D](
    other: ExchangerNode[D],
    selfMsg: Msg,
    stats: Statistics,
    params: Params,
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
    val mergedDesc = ctx.addAll(selfMsg.desc, other.msg.desc)
    assert(mergedDesc ne null, "Couldn't merge logs") // TODO: maybe retry?
    val resMsg = Msg(
      value = a,
      contK = newContK,
      contT = newContT,
      desc = mergedDesc,
      postCommit = ObjStack.Lst.concat(other.msg.postCommit, selfMsg.postCommit),
      // this thread will continue, so we use (and update) our data:
      exchangerData = selfMsg.exchangerData.updated(this.key, stats.exchanged(params))
    )
    debugLog(s"merged postCommit: ${resMsg.postCommit.mkString()} - thread#${Thread.currentThread().getId()}")
    Right(resMsg)
  }

  private[this] final def mergeConts[D](
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

  private[this] final def mergeContTs(
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

private final class DualExchangerImplJvm[A, B](
  final override val dual: PrimaryExchangerImplJvm[B, A],
) extends ExchangerImplJvm[A, B] {

  protected final override def incoming =
    dual.outgoing

  protected final override def outgoing =
    dual.incoming

  private[choam] final override def key =
    dual.key

  protected final override def initializeIfNeeded(retInc: Boolean): AtomicReferenceArray[ExchangerNode[_]] =
    dual.initializeIfNeeded(!retInc)
}

private final class PrimaryExchangerImplJvm[A, B] private[choam] (
) extends PrimaryExchangerImplJvmBase
  with ExchangerImplJvm[A, B] {

  final override val dual: Exchanger[B, A] =
    new DualExchangerImplJvm[B, A](this)

  protected[choam] final override val key =
    new Exchanger.Key

  protected[choam] final override def initializeIfNeeded(retInc: Boolean): AtomicReferenceArray[ExchangerNode[_]] = {
    val inc = this.incoming match {
      case null =>
        val newInc = ExchangerImplJvm.mkArray()
        this.cmpxchgIncoming(null, newInc) match {
          case null => newInc
          case other => other
        }
      case inc =>
        inc
    }
    val out = this.outgoing match {
      case null =>
        val newOut = ExchangerImplJvm.mkArray()
        this.cmpxchgOutgoing(null, newOut) match {
          case null => newOut
          case other => other
        }
      case out =>
        out
    }
    if (retInc) inc else out
  }

  protected[choam] final override def incoming: AtomicReferenceArray[ExchangerNode[_]] =
    this._incoming

  protected[choam] final override def outgoing: AtomicReferenceArray[ExchangerNode[_]] =
    this._outgoing
}

private object ExchangerImplJvm {

  private[choam] def unsafe[A, B]: Exchanger[A, B] = {
    new PrimaryExchangerImplJvm[A, B]()
  }

  private[choam] type StatMap =
    Map[Exchanger.Key, ExchangerImplJvm.Statistics]

  private[choam] final object StatMap {
    def empty: StatMap =
      Map.empty
  }

  // TODO: this could be packed into an Int
  private[choam] final case class Statistics(
    /* Always <= size */
    effectiveSize: Byte, // ---------------------------\
    /* Counts misses (++) and contention (--) */ //     ⊢--> missed/contended
    misses: Byte, // ----------------------------------/
    /* How much to wait for an exchange */
    spinShift: Byte, // -------------------------------\
    /* Counts exchanges (++) and rescinds (--) */ //    ⊢--> exchanged/rescinded
    exchanges: Byte // --------------------------------/
  ) {

    import Exchanger.Params

    require(effectiveSize > 0)

    /**
     * Couldn't exchange, so we may decrease effective size.
     */
    def missed(p: Params): Statistics = {
      if (this.misses == p.maxMisses) {
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
    def contended(size: Int, p: Params): Statistics = {
      if (this.misses == p.minMisses) {
        val newEffSize = Math.min(this.effectiveSize << 1, size)
        this.copy(effectiveSize = newEffSize.toByte, misses = 0.toByte)
      } else {
        this.copy(misses = (this.misses - 1).toByte)
      }
    }

    def exchanged(p: Params): Statistics = {
      if (this.exchanges == p.maxExchanges) {
        val newSpinShift = Math.min(this.spinShift + 1, p.maxSpinShift.toInt)
        this.copy(spinShift = newSpinShift.toByte, exchanges = 0.toByte)
      } else {
        this.copy(exchanges = (this.exchanges + 1).toByte)
      }
    }

    def rescinded(p: Params): Statistics = {
      if (this.exchanges == p.minExchanges) {
        val newSpinShift = Math.max(this.spinShift - 1, 0)
        this.copy(spinShift = newSpinShift.toByte, exchanges = 0.toByte)
      } else {
        this.copy(exchanges = (this.exchanges - 1).toByte)
      }
    }
  }

  private[choam] final object Statistics {

    private[this] val _zero: Statistics =
      Statistics(effectiveSize = 1.toByte, misses = 0.toByte, spinShift = 0.toByte, exchanges = 0.toByte)

    def zero: Statistics =
      _zero

    // TODO: these magic constants should be tuned with experiments


  }

  private[choam] val size = Math.min(
    // `availableProcessors` is guaranteed to return >= 1,
    // so this is always at least (1 + 1) / 2 = 1
    (Runtime.getRuntime().availableProcessors() + 1) >>> 1,
    0xFF
  )

  private[choam] def mkArray(): AtomicReferenceArray[ExchangerNode[_]] = {
    // TODO: use padded references
    new AtomicReferenceArray[ExchangerNode[_]](this.size)
  }
}
