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
package data

import scala.collection.immutable.ArraySeq

import cats.data.Chain

final class ArraySeqSpec extends BaseSpec {

  test("Chain.fromSeq(_: ArraySeq[_])") {
    val arr = Array.fill[String](3)("foo")
    arr(1) = "bar"
    val c = Chain.fromSeq[String](ArraySeq.unsafeWrapArray(arr))
    assertEquals(c.get(-2), None)
    assertEquals(c.get(-1), None)
    assertEquals(c.get(0), Some("foo"))
    assertEquals(c.get(1), Some("bar"))
    assertEquals(c.get(2), Some("foo"))
    assertEquals(c.get(3), None)
    assertEquals(c.get(4), None)
  }
}
