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
package async

abstract class AsyncStack[F[_], A] {
  def push: Rxn[A, Unit]
  def pop(implicit F: AsyncReactive[F]): F[A]
}

object AsyncStack {

  def apply[F[_], A]: Axn[AsyncStack[F, A]] =
    impl1[F, A]

  def impl1[F[_], A]: Axn[AsyncStack[F, A]] =
    AsyncStack1[F, A]

  def impl2[F[_], A]: Axn[AsyncStack[F, A]] =
    AsyncStack2[F, A]

  def impl3[F[_], A]: Axn[AsyncStack[F, A]] =
    AsyncStack3[F, A]
}
