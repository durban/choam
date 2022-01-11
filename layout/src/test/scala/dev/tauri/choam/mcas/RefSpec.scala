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

import scala.collection.JavaConverters._

import org.openjdk.jol.info.{ ClassLayout, FieldLayout }

import refs.Ref2

object RefSpec {

  final val targetSize = 128L

  private[this] final val id03 = Set("_id0", "_id1", "_id2", "_id3")
  private[this] final val id47 = Set("_id4", "_id5", "_id6", "_id7")

  final val fieldNames: Set[String] = Set(
    "version",
    "value",
    "marker",
  ).union(id03)

  final val fieldNamesA: Set[String] = Set(
    "versionA",
    "valueA",
    "markerA",
    "refA",
  ).union(id03)

  final val fieldNamesB: Set[String] = Set(
    "versionB",
    "valueB",
    "markerB",
    "refB",
  ).union(id47)
}

@deprecated("so that we can test deprecated methods", since = "we need it")
class RefSpec extends BaseSpecA {

  import RefSpec.targetSize

  def getLeftRightPaddedSize(
    obj: AnyRef,
    fieldNames: Set[String] = RefSpec.fieldNames,
  ): (Long, Long) = {
    val layout = ClassLayout.parseInstance(obj)
    println(layout.toPrintable(obj))
    val (firstField, lastField) = getFirstAndLastField(layout, fieldNames)
    val start = firstField.offset
    val leftPadded = start
    val end = layout.instanceSize
    val rightPadded = end - (lastField.offset + lastField.size)
    (leftPadded, rightPadded)
  }

  private[this] def getFirstAndLastField(
    layout: ClassLayout,
    fieldNames: Set[String],
  ): (FieldLayout, FieldLayout) = {
    val fields = layout.fields.iterator.asScala.toList
    val ff = fields.find(f => fieldNames.contains(f.name)).getOrElse {
      fail("no matching field found")
    }
    val lf = fields.findLast(f => fieldNames.contains(f.name)).getOrElse {
      fail("no matching field found")
    }
    (ff, lf)
  }

  test("Ref should be padded to avoid false sharing") {
    assumeOpenJdk()
    val ref = Ref.unsafe("foo")
    val (left, right) = getLeftRightPaddedSize(ref)
    assert((clue(left) >= targetSize) || (clue(right) >= targetSize))
  }

  test("Unpadded Ref should not be padded") {
    assumeOpenJdk()
    val ref = Ref.unsafeUnpadded("bar")
    assert(clue(ClassLayout.parseInstance(ref).instanceSize()) < targetSize)
  }

  test("Ref2 P1P1 should be double-padded") {
    assumeOpenJdk()
    val ref: Ref2[_, _] = Ref2.unsafeP1P1[String, Object]("bar", new AnyRef)
    val (left1, _) = getLeftRightPaddedSize(ref, RefSpec.fieldNamesA)
    val (left2, _) = getLeftRightPaddedSize(ref, RefSpec.fieldNamesB)
    assert(clue(left2) >= (clue(left1) + 256))
  }
}
