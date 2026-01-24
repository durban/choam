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

import cats.effect.{ IO, SyncIO }

import internal.mcas.{ Mcas, RefIdGen }
import core.{ Reactive, AsyncReactive }

abstract class StressTestBase {

  protected final val impl: Mcas =
    StressTestBase._mcasImpl

  protected final implicit def reactive: Reactive[SyncIO] =
    StressTestBase._reactiveForSyncIo

  protected final implicit def reactiveIO: AsyncReactive[IO] =
    StressTestBase._reactiveForIo

  protected final def rig: RefIdGen =
    this.impl.currentContext().refIdGen
}

object StressTestBase {

  private val _crt: ChoamRuntime = {
    // Note: we're never closing this, but
    // JCStress forks JVMs, so it will be
    // short-lived.
    ChoamRuntime.unsafeBlocking()
  }

  private val _reactiveForSyncIo: Reactive[SyncIO] = {
    Reactive.fromIn[SyncIO, SyncIO](_crt).allocated.unsafeRunSync()._1
  }

  private val _reactiveForIo: AsyncReactive[IO] = {
    AsyncReactive.fromIn[SyncIO, IO](_crt).allocated.unsafeRunSync()._1
  }

  private val _mcasImpl: Mcas = {
    this._crt.mcasImpl
  }

  val emcasInst: Mcas = {
    val e = _mcasImpl
    Predef.assert(e.getClass().getSimpleName() == "Emcas")
    e
  }
}
