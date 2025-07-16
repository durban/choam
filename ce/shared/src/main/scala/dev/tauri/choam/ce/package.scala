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

import cats.effect.{ IO, SyncIO }

import core.{ Reactive, AsyncReactive }

package object ce {

  /** Only for testing! */
  private[choam] final object unsafeImplicits {

    private[this] val _rt =
      ChoamRuntime.unsafeBlocking()

    private[this] val _reactiveSyncIo =
      Reactive.fromIn[SyncIO, SyncIO](_rt).allocated.unsafeRunSync()._1

    private[this] val _asyncReactiveIo =
      AsyncReactive.fromIn[SyncIO, IO](_rt).allocated.unsafeRunSync()._1

    implicit final def reactiveForSyncIO: Reactive[SyncIO] =
      _reactiveSyncIo

    implicit final def asyncReactiveForIO: AsyncReactive[IO] =
      _asyncReactiveIo
  }
}
