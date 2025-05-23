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
package helpers

final class DisableAssertionsSpec extends BaseSpec {

  test("_assert should have no effect") {
    _assert(false)
    _assert({ throw new Exception; false }) : @nowarn("cat=w-flag-dead-code")
  }

  test("Predef.assert should still work".fail) {
    Predef.assert(false)
  }

  test("Predef.assert should still work (with message)".fail) {
    Predef.assert(false, "foo")
  }

  test("munit assert should still work".fail) {
    this.assert(false)
  }

  test("require should still work".fail) {
    require(false)
  }

  test("require should still work (with message)".fail) {
    require(false, "foo")
  }

  test("impossible should still work".fail) {
    impossible("foo")
  }
}
