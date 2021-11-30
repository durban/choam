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

final class TreiberStackSpecNaiveKCAS
  extends TreiberStackSpec
  with SpecNaiveKCAS

final class TreiberStackSpecEMCAS
  extends TreiberStackSpec
  with SpecEMCAS

abstract class TreiberStackSpec extends BaseSpecA { this: KCASImplSpec =>

  test("TreiberStack should include the elements passed to its constructor") {
    assertEquals(new TreiberStack[Int]().unsafeToList(this.kcasImpl), Nil)
    assertEquals(new TreiberStack[Int](1 :: 2 :: 3 :: Nil).unsafeToList(this.kcasImpl), 3 :: 2 :: 1 :: Nil)
  }

  test("TreiberStack#unsafePop") {
    val s = new TreiberStack[Int](1 :: 2 :: 3 :: Nil)
    assertEquals(s.unsafePop.unsafeRun(this.kcasImpl), 3)
    assertEquals(s.unsafePop.unsafeRun(this.kcasImpl), 2)
    assertEquals(s.unsafePop.unsafeRun(this.kcasImpl), 1)
    assertEquals(s.unsafePop.?.unsafeRun(this.kcasImpl), None)
  }
}