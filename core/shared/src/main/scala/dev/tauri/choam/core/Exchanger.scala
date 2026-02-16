/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.LongAdder

private[choam] sealed trait Exchanger[A, B] {
  def exchange(a: A): Rxn[B]
  def dual: Exchanger[B, A]
  private[core] def key: Exchanger.Key
}

private[choam] object Exchanger extends ExchangerCompanionPlatform {

  private[choam] def apply[A, B]: Rxn[Exchanger[A, B]] =
    Rxn.unsafe.delay { this.unsafe[A, B] }

  private[choam] def profiled[A, B](counter: LongAdder): Rxn[Exchanger[A, B]] = {
    this.apply[A, B].flatMap { underlying =>
      Rxn.unsafe.delay {
        new ProfiledExchanger[A, B](
          d = null,
          underlying = underlying,
          counter = counter,
        )
      }
    }
  }

  private[core] final class Key {

    final override def toString: String =
      s"Key@${this.hashCode.toHexString}"
  }

  private[core] trait UnsealedExchanger[A, B]
    extends Exchanger[A, B]

  private[this] final class ProfiledExchanger[A, B](
    d: ProfiledExchanger[B, A],
    underlying: Exchanger[A, B],
    counter: LongAdder,
  ) extends UnsealedExchanger[A, B] {

    private[this] val isPrimary: Boolean =
      d eq null

    final override def exchange(a: A): Rxn[B] = {
      // Every exchange has 2 sides, so only
      // the primary side increments the counter:
      if (this.isPrimary) {
        underlying.exchange(a).postCommit(_ => Rxn.unsafe.delay {
          counter.increment()
        })
      } else {
        underlying.exchange(a)
      }
    }

    final override val dual: Exchanger[B, A] = {
      if (d ne null) d
      else new ProfiledExchanger[B, A](this, underlying.dual, counter)
    }

    private[core] final override val key =
      underlying.key
  }

  // TODO: these are JVM-only:

  import internal.mcas.{ Mcas, Descriptor }

  private[choam] val paramsKey =
    new Exchanger.Key

  // TODO: these are temporarily mutable for benchmarking
  @volatile
  private[core] var params: Params =
    Params()

  private[core] final case class Params(
    final val maxMisses: Byte =
      64,
    final val minMisses: Byte =
      -64,
    final val maxExchanges: Byte =
      4,
    final val minExchanges: Byte =
      -4,
    final val maxSizeShift: Byte =
      8,
    final val maxSpin: Int =
      1024,
    // these two are interdependent:
    final val defaultSpin: Int =
      128,
    final val maxSpinShift: Byte =
      16,
  )

  private[core] final case class Msg private (
    value: Either[Throwable, Any], // TODO: avoid Either by using (Any | Rxn.ExchangePanic)
    contK: ObjStack.Lst[Any],
    contT: Array[Byte],
    desc: Descriptor,
    postCommit: ObjStack.Lst[Rxn[Unit]],
    exchangerData: Rxn.ExStatMap,
    hasTentativeRead: Boolean,
    state: Msg.State,
  )

  private[core] object Msg {

    sealed abstract class State
    final object Initial extends State
    final object Claimed extends State
    final object Finished extends State

    final def newMsg(
      value: Any,
      contK: ObjStack.Lst[Any],
      contT: Array[Byte],
      desc: Descriptor,
      postCommit: ObjStack.Lst[Rxn[Unit]],
      exchangerData: Rxn.ExStatMap,
      hasTentativeRead: Boolean,
    ): Msg = {
      Msg(
        value = Right(value),
        contK = contK,
        contT = contT,
        desc = desc,
        postCommit = postCommit,
        exchangerData = exchangerData,
        hasTentativeRead = hasTentativeRead,
        state = Initial,
      )
    }

    final def fromClaimedExchange(
      value: Any,
      contK: ObjStack.Lst[Any],
      contT: Array[Byte],
      desc: Descriptor,
      postCommit: ObjStack.Lst[Rxn[Unit]],
      exchangerData: Rxn.ExStatMap,
      hasTentativeRead: Boolean,
    ): Msg = {
      Msg(
        value = Right(value),
        contK = contK,
        contT = contT,
        desc = desc,
        postCommit = postCommit,
        exchangerData = exchangerData,
        hasTentativeRead = hasTentativeRead,
        state = Claimed,
      )
    }

    final def fromFinishedExchange(fx: FinishedEx[?], newStats: Rxn.ExStatMap, ctx: Mcas.ThreadContext): Msg = {
      Msg(
        value = fx.result,
        contK = fx.contK,
        contT = fx.contT,
        desc = ctx.start().toImmutable, // TODO: could we avoid the `toImmutable`?
        postCommit = ObjStack.Lst.empty[Rxn[Unit]],
        exchangerData = newStats,
        hasTentativeRead = fx.hasTentativeRead,
        state = Finished,
      )
    }
  }

  private[core] sealed abstract class NodeResult[C]

  private[core] final class FinishedEx[C](
    val result: Either[Throwable, C],
    val contK: ObjStack.Lst[Any],
    val contT: Array[Byte],
    val hasTentativeRead: Boolean,
  ) extends NodeResult[C]

  private[core] final class Rescinded[C]
    extends NodeResult[C]

  private[core] final object Rescinded {
    def apply[C]: Rescinded[C] =
      _singleton.asInstanceOf[Rescinded[C]]
    private[this] val _singleton =
      new Rescinded[Any]
  }
}
