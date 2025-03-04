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
package ce

import cats.effect.{ IO, IOApp }

import stm.Transactive

/**
 * Mixin for convenient access to a `Transactive[IO]`
 * instance
 *
 * This trait is intended to be mixed into
 * an object extending [[cats.effect.IOApp]].
 * It provides an implicit `Transactive[IO]`.
 * instance. The resources needed by this
 * instance are acquired in the constructor, and
 * are never released. Thus, use only if the
 * `Transactive` is needed for the duration of
 * the whole `IOApp` program.
 *
 * @see [[dev.tauri.choam.stm.Transactive#forAsyncRes]]
 *      for more control over resource acquisition and release,
 *      and for any generic `F[_]` with a [[cats.effect.kernel.Async]]
 *      instance.
 */
trait TxnAppMixin extends BaseMixin { this: IOApp =>

  private[this] final val _transactiveForIO: Transactive[IO] =
    new Transactive.TransactiveImpl[IO](this.choamRuntime.mcasImpl)

  implicit protected[this] final def transactiveForIO: Transactive[IO] =
    this._transactiveForIO
}
