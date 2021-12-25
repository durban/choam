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
package mcas

import scala.collection.JavaConverters._

import cats.syntax.all._

import org.openjdk.jol.info.ClassLayout

import refs.Ref2

object RefSpec {
  final val fieldName = "value"
  final val targetSize = 160L
}

@deprecated("so that we can test deprecated methods", since = "we need it")
class RefSpec extends BaseSpecA {

  import RefSpec._

  def getLeftRightPaddedSize(obj: AnyRef, fieldName: String): (Long, Long) = {
    val layout = ClassLayout.parseInstance(obj)
    val fields = layout.fields.iterator.asScala.toList
    val valField = fields.filter(_.name === fieldName) match {
      case Nil => fail(s"no '${fieldName}' field found in ${obj} (of class ${obj.getClass})")
      case h :: Nil => h
      case more => fail(s"more than one '${fieldName}' field found: ${more}")
    }

    val start = valField.offset
    val leftPadded = start
    val end = layout.instanceSize
    val rightPadded = end - start
    (leftPadded, rightPadded)
  }

  test("Ref should be padded to avoid false sharing") {
    assumeOpenJdk()
    val ref = Ref.unsafe("foo")
    val (left, right) = getLeftRightPaddedSize(ref, fieldName)
    assert((clue(left) >= targetSize) || (clue(right) >= targetSize))
  }

  test("Unpadded Ref should not be padded (sanity check)") {
    assumeOpenJdk()
    val ref = Ref.unsafeUnpadded("bar")
    val (left, right) = getLeftRightPaddedSize(ref, fieldName)
    assert((clue(left) <= 48L) && (clue(right) <= 48L))
  }

  test("Ref2 P1P1 should be double-padded") {
    assumeOpenJdk()
    val ref: Ref2[_, _] = Ref2.unsafeP1P1[String, Object]("bar", new AnyRef)
    val (left1, _) = getLeftRightPaddedSize(ref, "valueA")
    val (left2, _) = getLeftRightPaddedSize(ref, "valueB")
    assert(clue(left2) >= (clue(left1) + 256))
  }
}
