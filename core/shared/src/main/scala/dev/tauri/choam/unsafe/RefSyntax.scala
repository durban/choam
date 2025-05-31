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
package unsafe

import core.Ref

/**
 * Extension methods for more convenient
 * handling of `Ref`s in an `atomically`
 * block.
 */
final class RefSyntax[A](private val self: Ref[A]) extends AnyVal {

  /** @see [[dev.tauri.choam.core.Ref.get]] */
  final def value(implicit ir: InRxn): A =
    ir.readRef(self.loc)

  /** @see [[dev.tauri.choam.core.Ref.set1]] */
  final def value_=(nv: A)(implicit ir: InRxn): Unit =
    ir.writeRef(self.loc, nv)
}
