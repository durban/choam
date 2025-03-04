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
package zi

import zio.{ Task, ZIOApp }
import zio.interop.catz.asyncInstance

import async.AsyncReactive

/**
 * Mixin for convenient access to an `AsyncReactive[Task]`
 * instance
 *
 * This trait is intended to be mixed into
 * an object extending [[zio.ZIOApp]].
 * It provides an implicit `AsyncReactive[Task]`.
 * instance. The resources needed by this
 * instance are acquired in the constructor, and
 * are never released. Thus, use only if the
 * `AsyncReactive` is needed for the duration of
 * the whole `ZIOApp` program.
 *
 * @see [[dev.tauri.choam.async.AsyncReactive#forAsyncRes]]
 *      for more control over resource acquisition and release,
 *      and for any generic `F[_]` with a [[cats.effect.kernel.Async]]
 *      instance.
 * @see [[dev.tauri.choam.core.Reactive#forSyncRes]] for a
 *      version which only requires a [[cats.effect.kernel.Sync]]
 *      instance.
 */
trait RxnAppMixin extends BaseMixin { this: ZIOApp =>

  private[this] final val _asyncReactiveForZIO: AsyncReactive[Task] =
    new AsyncReactive.AsyncReactiveImpl[Task](this.choamRuntime.mcasImpl)

  implicit protected[this] final def asyncReactiveForZIO: AsyncReactive[Task] =
    this._asyncReactiveForZIO
}
