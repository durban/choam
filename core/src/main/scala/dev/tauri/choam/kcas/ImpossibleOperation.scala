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
package kcas

import mcas.MemoryLocation

final class ImpossibleOperation private[kcas] (
  private[choam] val ref: MemoryLocation[_],
  private[choam] val a: HalfWordDescriptor[_],
  private[choam] val b: HalfWordDescriptor[_],
) extends IllegalArgumentException(
  s"Impossible k-CAS for ${ref}: ${a.ov} -> ${a.nv} and ${b.ov} -> ${b.nv}"
) {

  assert(a.address eq ref)
  assert(b.address eq ref)

  final override def fillInStackTrace(): Throwable =
    this
}
