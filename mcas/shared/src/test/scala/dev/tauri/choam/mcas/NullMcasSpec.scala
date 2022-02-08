/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

final class NullMcasSpec extends BaseSpecA {

  test("NullMcas must be able to perform an empty k-CAS") {
    val ctx = MCAS.NullMcas.currentContext()
    val d0 = ctx.start()
    assertEquals(d0.validTs, Version.Start)
    assert(ctx.tryPerformOk(d0))
    assertEquals(ctx.start().validTs, Version.Start)
  }
}