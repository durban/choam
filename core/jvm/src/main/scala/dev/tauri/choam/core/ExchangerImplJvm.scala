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
package core

import java.util.Arrays
import java.util.concurrent.atomic.AtomicReferenceArray

import internal.mcas.Mcas
import Exchanger.{ Msg, NodeResult, Rescinded, FinishedEx, Params }

private sealed trait ExchangerImplJvm[A, B]
  extends Exchanger.UnsealedExchanger[A, B] {

  import ExchangerImplJvm.{ size => _, _ }

  // TODO: could we use a single elimination array?
  protected def incoming: AtomicReferenceArray[ExchangerNode[?]]

  protected def outgoing: AtomicReferenceArray[ExchangerNode[?]]

  private[core] def key: Exchanger.Key

  protected def initializeIfNeeded(retInc: Boolean): AtomicReferenceArray[ExchangerNode[?]]

  final override def exchange: Rxn[A, B] =
    Rxn.internal.exchange[A, B](this)

  private[this] final val isDebug =
    false

  private[this] final def debugLog(msg: => String): Unit = { // TODO: make this "elidable"
    if (this.isDebug) {
      println(msg)
    }
  }

  private[core] final def tryExchange[C](
    msg: Msg,
    params: Params,
    ctx: Mcas.ThreadContext,
  ): Either[StatMap, Exchanger.Msg] = {
    // TODO: exchangerData grows forever
    val stats = msg.exchangerData.getOrElse(this.key, Statistics.zero).asInstanceOf[Int]
    val effSize = Statistics.effectiveSize(stats)
    debugLog(s"tryExchange (effectiveSize = ${effSize}) - thread#${Thread.currentThread().getId()}")
    val idx = if (effSize < 2) 0 else ctx.random.nextInt(effSize.toInt)
    tryIdx(idx, msg, stats, params, ctx) match {
      case Left(stats) => Left(msg.exchangerData.updated(this.key, stats))
      case Right(msg) => Right(msg)
    }
  }

  private[this] final def tryIdx[C](idx: Int, msg: Msg, stats: Statistics, params: Params, ctx: Mcas.ThreadContext): Either[Statistics, Msg] = {
    debugLog(s"tryIdx(${idx}) - thread#${Thread.currentThread().getId()}")
    val incoming = this.incoming match {
      case null =>
        this.initializeIfNeeded(retInc = true)
      case array =>
        array
    }
    _assert(incoming ne null)
    // post our message:
    incoming.get(idx) match {
      case null =>
        // empty slot, insert ourselves:
        val self = new ExchangerNode[C](
          msg,
          Ref.unsafePadded[NodeResult[C]](null, ctx.refIdGen),
        )
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
                Left(
                  Statistics.rescinded(
                    Statistics.missed(stats, params),
                    params,
                  )
                )
              }
            case other: ExchangerNode[_] =>
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
                  Left(Statistics.rescinded(stats, params))
                }
              } else {
                // someone else claimed our offer, we can't continue with fulfillment,
                // so we wait for our offer to be fulfilled (and retry if it doesn't happen):
                waitForClaimedOffer(self, msg, None, stats, params, ctx)
              }
          }
        } else {
          // contention, will retry
          Left(Statistics.contended(stats, incoming.length(), params))
        }
      case _ =>
        // contention, will retry
        Left(Statistics.contended(stats, incoming.length(), params))
    }
  }

  /** Our offer have been claimed by somebody, so we wait for them to fulfill it */
  private[this] final def waitForClaimedOffer[C](
    self: ExchangerNode[C],
    msg: Msg,
    maybeResult: Option[NodeResult[C]],
    stats: Statistics,
    params: Params,
    ctx: Mcas.ThreadContext,
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
            val newStats = msg.exchangerData.updated(this.key, Statistics.exchanged(stats, params))
            Right(Msg.fromFinishedEx(fx, newStats, ctx))
          case _: Rescinded[_] =>
            // we're the only one who can rescind this
            impossible("Someone rescinded our Node!")
        }
      case None =>
        if (ctx.singleCasDirect(self.hole.loc, null, Rescinded[C])) {
          // OK, we rolled back, and can retry
          debugLog(s"waitForClaimedOffer: rolled back - thread#${Thread.currentThread().getId()}")
          Left(Statistics.rescinded(stats, params))
        } else {
          // couldn't roll back, it must be a result
          ctx.readDirect(self.hole.loc) match {
            case fx: FinishedEx[_] =>
              debugLog(s"waitForClaimedOffer: found result - thread#${Thread.currentThread().getId()}")
              val newStats = msg.exchangerData.updated(this.key, Statistics.exchanged(stats, params))
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
    ctx: Mcas.ThreadContext,
  ): Either[Statistics, Msg] = {
    val a: A = selfMsg.value.asInstanceOf[A]
    val b: B = other.msg.value.asInstanceOf[B]
    debugLog(s"fulfillClaimedOffer: selfMsg.value = ${a}; other.msg.value = ${b} - thread#${Thread.currentThread().getId()}")
    val (newContT, newContK) = mergeConts[D](
      selfContT = selfMsg.contT,
      // we put `b` on top of contK; `FinishExchange` will pop it:
      selfContK = ListObjStack.Lst[Any](b, selfMsg.contK),
      otherContT = other.msg.contT,
      otherContK = other.msg.contK,
      hole = other.hole,
    )
    debugLog(s"merged conts: newContT = ${java.util.Arrays.toString(newContT)}; newContK = [${ListObjStack.Lst.mkString(newContK)}] - thread#${Thread.currentThread().getId()}")
    val mergedDesc = try {
      ctx.addAll(selfMsg.desc, other.msg.desc) // returns null if we can't extend
    } catch {
      case _: internal.mcas.Hamt.IllegalInsertException =>
        debugLog("can't merge overlapping descriptors")
        null
    }
    if (mergedDesc ne null) {
      debugLog(s"merged logs - thread#${Thread.currentThread().getId()}")
      val resMsg = Msg(
        value = a,
        contK = newContK,
        contT = newContT,
        desc = mergedDesc,
        postCommit = ListObjStack.Lst.concat(other.msg.postCommit, selfMsg.postCommit),
        // this thread will continue, so we use (and update) our data:
        exchangerData = selfMsg.exchangerData.updated(this.key, Statistics.exchanged(stats, params))
      )
      debugLog(s"merged postCommit: ${ListObjStack.Lst.mkString(resMsg.postCommit)} - thread#${Thread.currentThread().getId()}")
      Right(resMsg)
    } else {
      debugLog(s"Couldn't merge logs (or can't extend) - thread#${Thread.currentThread().getId()}")
      // from the point of view of the Exchanger, this is a
      // "successful" exchange -- the reason we'll have to
      // retry is the incompatible descriptors, so we count
      // this as an exchange in the stats:
      Left(Statistics.exchanged(stats, params))
      // Note, that while this may seem like an "unconditional"
      // retry (and thus, not lock-free), there are 2 cases, and
      // both are fine:
      // - The reason for retry is that we can't extend; this means
      //   some other thread committed, so we're fine.
      // - The reason for retry is that the 2 descriptors
      //   were overlapping (i.e., contained at least one ref
      //   which was common between them). In this case there
      //   really are no "progress"; however, the whole `Exchanger`
      //   mechanism is really just an optimization for adding
      //   "elimination" to try to increase performance of otherwise
      //   lock-free operations. This is the reason `Exchanger`
      //   by itself is `unsafe` (and not part of the public
      //   API). So if the original operation is lock-free, then
      //   the exchange "failing" here and retrying (the original,
      //   lock-free operation) _preserves_ lock-freedom. And
      //   that's enough for us here.
    }
  }

  private[this] final def mergeConts[D](
    selfContT: Array[Byte],
    selfContK: ListObjStack.Lst[Any],
    otherContT: Array[Byte],
    otherContK: ListObjStack.Lst[Any],
    hole: Ref[NodeResult[D]],
  ): (Array[Byte], ListObjStack.Lst[Any]) = {
    // otherContK: |-|-|-|-...-|COMMIT|-|-...-|
    //             \-----------/
    // we'll need this first part (until the first commit)
    // and also need an extra op (see below)
    // after this, we'll continue with selfContK
    // (extra op: to fill `other.hole` with the result
    // and also the remaining part of otherContK and otherContT)
    ListObjStack.Lst.splitBefore[Any](lst = otherContK, item = Rxn.commitSingleton) match {
      case (prefix, rest) =>
        val extraOp = Rxn.internal.finishExchange[D](
          hole = hole,
          restOtherContK = rest,
          lenSelfContT = selfContT.length,
        )
        val newContK = ListObjStack.Lst.concat(
          prefix,
          ListObjStack.Lst(extraOp, selfContK),
        )
        val newContT = mergeContTs(selfContT = selfContT, otherContT = otherContT)
        (newContT, newContK)
      case null =>
        val len = ListObjStack.Lst.length(otherContK)
        if (len == 0) impossible("empty otherContK")
        else impossible(s"no commit in otherContK: ${otherContK.mkString()}")
    }
  }

  private[this] final def mergeContTs(
    selfContT: Array[Byte],
    otherContT: Array[Byte],
  ): Array[Byte] = {
    // The top of the stack has the _highest_ index (see `ByteStack`)
    val selfContTLength = selfContT.length
    val otherContTLength = otherContT.length
    val res = Arrays.copyOf(selfContT, selfContTLength + otherContTLength)
    System.arraycopy(otherContT, 0, res, selfContTLength, otherContTLength)
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

  private[core] final override def key =
    dual.key

  protected final override def initializeIfNeeded(retInc: Boolean): AtomicReferenceArray[ExchangerNode[?]] =
    dual.initializeIfNeeded(!retInc)
}

private final class PrimaryExchangerImplJvm[A, B] private[core] (
) extends PrimaryExchangerImplJvmBase
  with ExchangerImplJvm[A, B] {

  final override val dual: Exchanger[B, A] =
    new DualExchangerImplJvm[B, A](this)

  protected[core] final override val key =
    new Exchanger.Key

  protected[core] final override def initializeIfNeeded(retInc: Boolean): AtomicReferenceArray[ExchangerNode[?]] = {
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

  protected[core] final override def incoming: AtomicReferenceArray[ExchangerNode[?]] =
    this._incoming

  protected[core] final override def outgoing: AtomicReferenceArray[ExchangerNode[?]] =
    this._outgoing
}

private object ExchangerImplJvm {

  private[core] def unsafe[A, B]: Exchanger[A, B] = {
    new PrimaryExchangerImplJvm[A, B]()
  }

  private[core] type StatMap =
    Map[Exchanger.Key, Any]

  private[core] final object StatMap {
    def empty: StatMap =
      Map.empty
  }

  final type Statistics =
    Int

  final object Statistics {

    import java.lang.Byte.toUnsignedInt
    import Exchanger.Params

    final def apply(
      /* Always <= size */
      effectiveSize: Byte, // ---------------------------\
      /* Counts misses (++) and contention (--) */ //     ⊢--> missed/contended
      misses: Byte, // ----------------------------------/
      /* How much to wait for an exchange */
      spinShift: Byte, // -------------------------------\
      /* Counts exchanges (++) and rescinds (--) */ //    ⊢--> exchanged/rescinded
      exchanges: Byte, // --------------------------------/
    ): Int = pack(
      effectiveSize = effectiveSize,
      misses = misses,
      spinShift = spinShift,
      exchanges = exchanges,
    )

    private[this] final def pack(
      effectiveSize: Byte,
      misses: Byte,
      spinShift: Byte,
      exchanges: Byte,
    ): Int ={

      var result: Int = toUnsignedInt(exchanges)
      result |= (toUnsignedInt(spinShift) << 8)
      result |= (toUnsignedInt(misses) << 16)
      result |= (toUnsignedInt(effectiveSize) << 24)
      result
    }

    final def withMisses(stats: Int, misses: Byte): Int = {
      (stats & 0xff00ffff) | (toUnsignedInt(misses) << 16)
    }

    final def withExchanges(stats: Int, exchanges: Byte): Int = {
      (stats & 0xffffff00) | toUnsignedInt(exchanges)
    }

    final def effectiveSize(stats: Int): Byte = {
      (stats >>> 24).toByte
    }

    final def misses(stats: Int): Byte = {
      (stats >>> 16).toByte
    }

    final def spinShift(stats: Int): Byte = {
      (stats >>> 8).toByte
    }

    final def exchanges(stats: Int): Byte = {
      stats.toByte
    }

    /**
     * Couldn't exchange, so we may decrease effective size.
     */
    final def missed(stats: Int, p: Params): Int = {
      if (misses(stats) == p.maxMisses) {
        val newEffSize = Math.max(effectiveSize(stats) >> 1, 1)
        pack(
          effectiveSize = newEffSize.toByte,
          misses = 0.toByte,
          spinShift = spinShift(stats),
          exchanges = exchanges(stats),
        )
      } else {
        withMisses(stats, misses = (misses(stats) + 1).toByte)
      }
    }

    /**
     * An exchange was lost due to 3rd party, so we may
     * increase effective size.
     */
    final def contended(stats: Int, size: Int, p: Params): Int = {
      if (misses(stats) == p.minMisses) {
        val newEffSize = Math.min(effectiveSize(stats).toInt << 1, size)
        pack(
          effectiveSize = newEffSize.toByte,
          misses = 0.toByte,
          spinShift = spinShift(stats),
          exchanges = exchanges(stats),
        )
      } else {
        withMisses(stats, misses = (misses(stats) - 1).toByte)
      }
    }

    final def exchanged(stats: Int, p: Params): Int = {
      if (exchanges(stats) == p.maxExchanges) {
        val newSpinShift = Math.min(spinShift(stats) + 1, p.maxSpinShift.toInt)
        pack(
          effectiveSize = effectiveSize(stats),
          misses = misses(stats),
          spinShift = newSpinShift.toByte,
          exchanges = 0.toByte,
        )
      } else {
        withExchanges(stats, exchanges = (exchanges(stats) + 1).toByte)
      }
    }

    final def rescinded(stats: Int, p: Params): Statistics = {
      if (exchanges(stats) == p.minExchanges) {
        val newSpinShift = Math.max(spinShift(stats) - 1, 0)
        pack(
          effectiveSize = effectiveSize(stats),
          misses = misses(stats),
          spinShift = newSpinShift.toByte,
          exchanges = 0.toByte,
        )
      } else {
        withExchanges(stats, exchanges = (exchanges(stats) - 1).toByte)
      }
    }

    final def zero: Statistics =
      0
  }

  private[core] val size = Math.min(
    // `availableProcessors` is guaranteed to return >= 1,
    // so this is always at least (1 + 1) / 2 = 1
    // TODO: According to the javadoc, we should
    // TODO: "occasionally poll this property"
    // TODO: (availableProcessors) because it may change!
    (Runtime.getRuntime().availableProcessors() + 1) >>> 1,
    0xFF
  )

  private[core] def mkArray(): AtomicReferenceArray[ExchangerNode[?]] = {
    // TODO: use padded references (or: make it configurable)
    new AtomicReferenceArray[ExchangerNode[?]](this.size)
  }
}
