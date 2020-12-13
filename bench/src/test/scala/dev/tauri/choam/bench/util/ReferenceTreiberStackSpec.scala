/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package util

class ReferenceTreiberStackSpec extends BaseSpec {

  "length" should "be correct" in {
    new ReferenceTreiberStack[Int].length should === (0)
    new ReferenceTreiberStack[Int](Nil).length should === (0)
    new ReferenceTreiberStack[Int](1 :: 2 :: 3 :: Nil).length should === (3)
  }
}
