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

sealed trait Exchanger[A, B] {
  def exchange: Rxn[A, B]
  def dual: Exchanger[B, A]
  private[choam] def key: AnyRef
}

/** Private, because an `Exchanger` is unsafe (may block indefinitely) */
private object Exchanger extends ExchangerCompanionPlatform {

  private[choam] def apply[A, B]: Axn[Exchanger[A, B]] =
    Rxn.unsafe.delay { _ => this.unsafe[A, B] }

  private[choam] trait UnsealedExchanger[A, B]
    extends Exchanger[A, B]

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
