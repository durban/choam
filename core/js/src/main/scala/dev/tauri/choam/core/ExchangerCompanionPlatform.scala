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
package core

private[choam] abstract class ExchangerCompanionPlatform { // TODO: should be private[core]

  private[choam] def unsafe[A, B](debugId: Option[Long]): Exchanger[A, B] =
    new ExchangerImplJs[A, B]

  private[choam] def unsafe[A, B]: Exchanger[A, B] =
    new ExchangerImplJs[A, B]
}
