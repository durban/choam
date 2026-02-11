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

import mcas.GlobalRefIdGen

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
    assert((clue(versionOffset) >= 64L) && (versionOffset < 128L))
    val markerStart: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "marker"))
    val markerOffset = (markerStart - start).toLong
    assert((clue(markerOffset) >= 64L) && (markerOffset < 128L))
  }

  test("Padding: RefP1P1 (SN)") {
    val ref: RefP1P1[String, AnyRef] = new RefP1P1[String, AnyRef]("foo", "bar", 42L, 43L)
    val start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.castObjectToRawPtr(ref))
    // TODO: these tests are commented out due to https://github.com/scala-native/scala-native/issues/4777
    // val value1Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "valueA"))
    // val value1Offset: Long = (value1Start - start).toLong
    // assert(
    //   (clue(value1Offset) >= 64L) && // the 1st field-group is in the base class,
    //   (value1Offset < 128L)          // so we assume it'll be first in the layout
    // )
    // val version1Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "versionA"))
    // val version1Offset: Long = (version1Start - start).toLong
    // assert((clue(version1Offset) >= 64L) && (version1Offset < 128L))
    // val marker1Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "markerA"))
    // val marker1Offset = (marker1Start - start).toLong
    // assert((clue(marker1Offset) >= 64L) && (marker1Offset < 128L))
    val value2Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "valueB"))
    val value2Offset: Long = (value2Start - start).toLong
    assert(
      (clue(value2Offset) >= 128L) && // the 2nd field-group is in the derived class,
      (value2Offset < 192L)           // so we assume it'll be second in the layout
    )
    val version2Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "versionB"))
    val version2Offset: Long = (version2Start - start).toLong
    assert((clue(version2Offset) >= 128L) && (version2Offset < 192L))
    val marker2Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "markerB"))
    val marker2Offset = (marker2Start - start).toLong
    assert((clue(marker2Offset) >= 128L) && (marker2Offset < 192L))
  }

  test("Padding: RefP2 (SN)") {
    val minOff = 64L  // there must be padding at the start,
    val maxOff = 128L // but all the fields are in a single group
    val ref: RefP2[AnyRef, String] = new RefP2[AnyRef, String]("foo", "bar", 42L, 43L)
    val start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.castObjectToRawPtr(ref))
    val value1Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "valueA"))
    val value1Offset: Long = (value1Start - start).toLong
    assert((clue(value1Offset) >= minOff) && (value1Offset < maxOff))
    val version1Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "versionA"))
    val version1Offset: Long = (version1Start - start).toLong
    assert((clue(version1Offset) >= minOff) && (version1Offset < maxOff))
    val marker1Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "markerA"))
    val marker1Offset = (marker1Start - start).toLong
    assert((clue(marker1Offset) >= minOff) && (marker1Offset < maxOff))
    val value2Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "valueB"))
    val value2Offset: Long = (value2Start - start).toLong
    assert((clue(value2Offset) >= minOff) && (value2Offset < maxOff))
    val version2Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "versionB"))
    val version2Offset: Long = (version2Start - start).toLong
    assert((clue(version2Offset) >= minOff) && (version2Offset < maxOff))
    val marker2Start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(ref, "markerB"))
    val marker2Offset = (marker2Start - start).toLong
    assert((clue(marker2Offset) >= minOff) && (marker2Offset < maxOff))
  }

  test("Padding: GlobalRefIdGen (SN)") {
    // there must be padding at the start,
    // and there is a single field:
    val expOff = 64L
    val rig: GlobalRefIdGen = GlobalRefIdGen.newGlobalRefIdGenForTesting()
    val start: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.castObjectToRawPtr(rig))
    val ctrStart: Ptr[Byte] = fromRawPtr[Byte](Intrinsics.classFieldRawPtr(rig, "ctr"))
    val ctrOffset: Long = (ctrStart - start).toLong
    assertEquals(ctrOffset, expOff)
  }

  test("Padding: GlobalContext (SN)".ignore) {
    // TODO: we can't test this now due to https://github.com/scala-native/scala-native/issues/4777
  }
}
