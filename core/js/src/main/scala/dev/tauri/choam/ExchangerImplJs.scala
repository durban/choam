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

import mcas.MCAS

private final class ExchangerImplJs[A, B](d: ExchangerImplJs[B, A] = null)
  extends Exchanger.UnsealedExchanger[A, B] {

    final override def exchange: Rxn[A, B] =
      Rxn.unsafe.retry[A, B]

    // NB: this MUST be initialized before `dual`,
    // otherwise it could remain uninitialized (null).
    private[choam] final override val key = {
      if (d ne null) d.key
      else new Exchanger.Key
    }

    final override val dual: Exchanger[B, A] = {
      if (d ne null) d
      else new ExchangerImplJs[B, A](this)
    }

    private[choam] final def tryExchange[C](
      @unused msg: Exchanger.Msg,
      @unused ctx: MCAS.ThreadContext
    ): Either[Rxn.ExStatMap, Exchanger.Msg] = {
      impossible("ExchangerImplJs.tryExchange")
    }
}
