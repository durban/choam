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
package internal
package refs

import scala.scalanative.runtime.{ Intrinsics, fromRawPtr }
import scala.scalanative.unsafe.Ptr

final class PaddingSpec extends BaseSpec {

  test("Padding: RefP1 (SN)") {
    val ref: RefP1[String] = new RefP1[String]("foo", 42L)
    val start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.castObjectToRawPtr(ref))
    val valueStart: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "value"))
    val valueOffset: Long = (valueStart - start).toLong
    assert(
      (clue(valueOffset) >= 64L) && // SN seems to default to 64-byte padding; probably "good enough"
      (valueOffset < 128L) // make sure the fields are "together" (i.e., no padding between them)
    )
    val versionStart: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "version"))
    val versionOffset: Long = (versionStart - start).toLong
    assert((clue(versionOffset) >= 64L) && (valueOffset < 128L))
    val markerStart: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "marker"))
    val markerOffset = (markerStart - start).toLong
    assert((clue(markerOffset) >= 64L) && (markerOffset < 128L))
  }
}
