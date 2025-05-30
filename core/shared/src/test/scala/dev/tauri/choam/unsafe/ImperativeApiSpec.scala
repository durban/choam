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
package unsafe

import munit.FunSuite

import core.Ref

final class ImperativeApiSpec extends FunSuite with MUnitUtils {

  test("Hello, World!") {

    def write(ref: Ref[Int])(implicit txn: InRxn): Unit = {
      writeRef(ref, 99)
    }

    def read(ref: Ref[Int])(implicit txn: InRxn): Int = {
      readRef(ref)
    }

    def myTxn()(implicit txn: InRxn): (Int, Int, Ref[Int]) = {
      val ref = newRef(42)
      val v1 = read(ref)
      write(ref)
      val v2 = read(ref)
      (v1, v2, ref)
    }

    val (v1, v2, ref) = atomically { implicit txn =>
      myTxn()
    }

    assertEquals(v1, 42)
    assertEquals(v2, 99)
    val v3 = atomically(readRef(ref)(_))
    assertEquals(v3, 99)
  }
}
