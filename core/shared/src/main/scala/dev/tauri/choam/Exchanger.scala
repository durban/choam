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

import java.util.concurrent.atomic.LongAdder

sealed trait Exchanger[A, B] {
  def exchange: Rxn[A, B]
  def dual: Exchanger[B, A]
  private[choam] def key: Exchanger.Key
}

/** Private, because an `Exchanger` is unsafe (may block indefinitely) */
private object Exchanger extends ExchangerCompanionPlatform {

  private[choam] def apply[A, B]: Axn[Exchanger[A, B]] =
    Rxn.unsafe.delay { _ => this.unsafe[A, B] }

  private[choam] def profiled[A, B](counter: LongAdder): Axn[Exchanger[A, B]] = {
    this.apply[A, B].flatMapF { underlying =>
      Rxn.unsafe.delay { _ =>
        new ProfiledExchanger[A, B](
          d = null,
          underlying = underlying,
          counter = counter,
        )
      }
    }
  }

  private[choam] final class Key
    extends Serializable {

    final override def toString: String =
      s"Key@${this.hashCode.toHexString}"
  }

  private[choam] trait UnsealedExchanger[A, B]
    extends Exchanger[A, B]

  private[this] final class ProfiledExchanger[A, B](
    d: ProfiledExchanger[B, A],
    underlying: Exchanger[A, B],
    counter: LongAdder,
  ) extends UnsealedExchanger[A, B] {

    private[this] val isPrimary: Boolean =
      d eq null

    final override def exchange: Rxn[A, B] = {
      // Every exchange has 2 sides, so only
      // the primary side increments the counter:
      if (this.isPrimary) {
        underlying.exchange.postCommit(Rxn.unsafe.delay { _ =>
          counter.increment()
        })
      } else {
        underlying.exchange
      }
    }

    final override val dual: Exchanger[B, A] = {
      if (d ne null) d
      else new ProfiledExchanger[B, A](this, underlying.dual, counter)
    }

    private[choam] final override val key =
      underlying.key
  }

  // TODO: these are JVM-only:

  import mcas.{ MCAS, HalfEMCASDescriptor }

  private[choam] final case class Msg(
    value: Any,
    contK: ObjStack.Lst[Any],
    contT: Array[Byte],
    desc: HalfEMCASDescriptor,
    postCommit: ObjStack.Lst[Axn[Unit]],
    exchangerData: Rxn.ExStatMap,
  )

  private[choam] object Msg {

    def fromFinishedEx(fx: FinishedEx[_], newStats: Rxn.ExStatMap, ctx: MCAS.ThreadContext): Msg = {
      Msg(
        value = fx.result,
        contK = fx.contK,
        contT = fx.contT,
        desc = ctx.start(),
        postCommit = null : ObjStack.Lst[Axn[Unit]],
        exchangerData = newStats,
      )
    }
  }

  private[choam] sealed abstract class NodeResult[C]

  private[choam] final class FinishedEx[C](
    val result: C,
    val contK: ObjStack.Lst[Any],
    val contT: Array[Byte],
  ) extends NodeResult[C]

  private[choam] final class Rescinded[C]
    extends NodeResult[C]

  private[choam] final object Rescinded {
    def apply[C]: Rescinded[C] =
      _singleton.asInstanceOf[Rescinded[C]]
    private[this] val _singleton =
      new Rescinded[Any]
  }
}
