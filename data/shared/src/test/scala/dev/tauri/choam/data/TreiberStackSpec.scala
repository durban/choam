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
package data

final class TreiberStackSpecThreadConfinedMCAS
  extends TreiberStackSpec
  with SpecThreadConfinedMCAS

abstract class TreiberStackSpec extends BaseSpecA { this: KCASImplSpec =>

  test("TreiberStack should include the elements passed to its constructor") {
    val s1 = TreiberStack.fromList[Int](Nil).unsafePerform(null, this.kcasImpl)
    assertEquals(s1.unsafeToList(this.kcasImpl), Nil)
    val s2 = TreiberStack.fromList[Int](List(1, 2, 3)).unsafePerform(null, this.kcasImpl)
    assertEquals(s2.unsafeToList(this.kcasImpl), List(3, 2, 1))
  }
}
