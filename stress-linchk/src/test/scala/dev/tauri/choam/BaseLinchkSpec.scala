/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.concurrent.duration._

import munit.{ BaseFunSuite, Location }

trait BaseLinchkSpec extends BaseFunSuite with LinchkUtils {

  override def munitTimeout: Duration =
    4.minutes

  final override def test(name: String)(body: => Any)(implicit loc: Location): Unit = {
    super[BaseFunSuite].test(name) {
      // lincheck tests seem unstable in CI windows:
      assumeNotWin()
      body
    } (loc)
  }

  protected def assumeNotWin(): Unit = {
    val isWin = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")
    this.assume(!isWin)
  }
}
