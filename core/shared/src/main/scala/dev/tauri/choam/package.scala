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

package dev.tauri

package object choam extends internal.ChoamUtils {

  final type =#>[-A, +B] = core.Rxn[A, B]

  final type Reactive[F[_]] = core.Reactive[F]

  final val Reactive: core.Reactive.type = core.Reactive

  final type Ref[A] = refs.Ref[A]

  final val Ref: refs.Ref.type = refs.Ref

  final type RefLike[A] = refs.RefLike[A]

  final val RefLike: refs.RefLike.type = refs.RefLike

  private[choam] implicit final class AxnSyntax2[A](private val self: core.Axn[A]) extends AnyVal {

    private[choam] final def unsafeRun(mcas: internal.mcas.Mcas): A = {
      self.unsafePerform(null : Any, mcas)
    }
  }
}
