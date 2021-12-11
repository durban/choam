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
package data

final class Counter(ref: Ref[Long]) {

  val add: Rxn[Long, Long] = ref.upd[Long, Long] { (cnt, n) =>
    (cnt + n, cnt)
  }

  val incr: Axn[Long] =
    add.provide(1L)

  val decr: Axn[Long] =
    add.provide(-1L)

  val count: Axn[Long] =
    add.provide(0L)
}

object Counter {

  def apply: Axn[Counter] =
    Ref(0L).map(new Counter(_))

  private[choam] def unsafe(): Counter =
    new Counter(Ref.unsafe(0L))
}
