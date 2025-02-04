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

import stm.Transactive

/**
 * Mixin for convenient access to a `Transactive[Task]`
 *
 * This trait is intended to be mixed into
 * an object extending [[zio.ZIOApp]].
 * It provides an implicit `Transactive[Task]`.
 * instance. The resources needed by this
 * instance are acquired in the constructor, and
 * are never released. Thus, use only if the
 * `Transactive` is needed for the duration of
 * the whole `ZIOApp` program.
 */
trait TxnAppMixin extends BaseMixin { this: ZIOApp =>

  private[this] final val _transactiveForZIO: Transactive[Task] =
    new Transactive.TransactiveImpl[Task](this._mcasImpl)

  implicit protected[this] final def transactiveForZIO: Transactive[Task] =
    this._transactiveForZIO
}
