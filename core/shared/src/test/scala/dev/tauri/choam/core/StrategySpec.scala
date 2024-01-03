/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package core

import scala.concurrent.duration._

import Rxn.Strategy

final class StrategySpec extends BaseSpec {

  test("Rxn.Strategy constructors") {
    val s1: Strategy.Spin = Strategy.spin(
      maxRetries = Some(42),
      maxSpin = 999,
      randomizeSpin = false,
    )
    assertEquals(s1.canSuspend, false)
    assertEquals(s1.maxRetries, Some(42))
    assertEquals(s1.maxRetriesInt, 42)
    assertEquals(s1.maxSpin, 999)
    assertEquals(s1.randomizeSpin, false)

    val s2: Strategy = Strategy.cede(
      maxRetries = Some(42),
      maxSpin = 999,
      randomizeSpin = false,
    )
    assertEquals(s2.canSuspend, true)
    assertEquals(s2.maxRetries, Some(42))
    assertEquals(s2.maxRetriesInt, 42)
    assertEquals(s2.maxSpin, 999)
    assertEquals(s2.randomizeSpin, false)

    val s3: Strategy = Strategy.sleep(
      maxRetries = Some(42),
      maxSpin = 999,
      randomizeSpin = false,
      maxSleep = 1.second,
      randomizeSleep = true,
    )
    assertEquals(s3.canSuspend, true)
    assertEquals(s3.maxRetries, Some(42))
    assertEquals(s3.maxRetriesInt, 42)
    assertEquals(s3.maxSpin, 999)
    assertEquals(s3.randomizeSpin, false)
    assertEquals(s3.maxSleep, 1.second)
    assertEquals(s3.randomizeSleep, true)
  }

  test("Rxn.Strategy copy") {
    val s1: Strategy.Spin = Strategy.Default
    assertEquals(s1.canSuspend, false)
    val s2: Strategy.Spin = s1.withMaxRetries(Some(42))
    assertEquals(s2.canSuspend, false)
    assertEquals(s2.maxRetries, Some(42))
    assertEquals(s2.maxRetriesInt, 42)
    val s3: Strategy.Spin = s2.withMaxRetries(None).withMaxSpin(999)
    assertEquals(s3.canSuspend, false)
    assertEquals(s3.maxRetries, None)
    assertEquals(s3.maxRetriesInt, -1)
    val s4: Strategy.Spin = s3.withRandomizeSpin(false)
    assertEquals(s4.canSuspend, false)
    assertEquals(s4.maxRetries, None)
    assertEquals(s4.maxRetriesInt, -1)
    assertEquals(s4.randomizeSpin, false)

    val s5: Strategy = s4.withMaxSleep(1.second)
    assertEquals(s5.canSuspend, true)
    assertEquals(s5.maxRetries, None)
    assertEquals(s5.maxRetriesInt, -1)
    assertEquals(s5.randomizeSpin, false)
    assertEquals(s5.maxSleep, 1.second)
    assertEquals(s5.randomizeSleep, true)
    val s6: Strategy = s5.withRandomizeSleep(false)
    assertEquals(s6.canSuspend, true)
    assertEquals(s6.maxRetries, None)
    assertEquals(s6.maxRetriesInt, -1)
    assertEquals(s6.randomizeSpin, false)
    assertEquals(s6.maxSleep, 1.second)
    assertEquals(s6.randomizeSleep, false)
  }
}
