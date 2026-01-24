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
package ce

import cats.effect.IOApp

private[ce] trait BaseMixin { this: IOApp =>

  private[this] final val _choamRuntime: ChoamRuntime = {
    // This might block, but as we're in the
    // constructor of an `IOApp`, it's probably
    // not a big deal (there is likely other
    // similar initialization going on anyway);
    // also, we're sure there is no `Rxn`
    // running yet.
    ChoamRuntime.unsafeBlocking()
  }

  protected[this] final def choamRuntime: ChoamRuntime = {
    this._choamRuntime
  }
}
