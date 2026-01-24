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
package core

import scala.concurrent.duration._

import cats.Show
import cats.syntax.all._

import dev.tauri.choam.{ RetryStrategy => Strategy }

final class StrategySpec extends BaseSpec {

  test("RetryStrategy constructors") {
    val s1: Strategy.CanSuspend[false] = Strategy.spin(
      maxRetries = Some(42),
      maxSpin = 999,
      randomizeSpin = false,
    )
    assertEquals(s1.canSuspend, false)
    assertEquals(s1.maxRetries, Some(42))
    assertEquals(s1.maxRetriesInt, 42)
    assertEquals(s1.maxSpin, 999)
    assertEquals(s1.randomizeSpin, false)

    val s2: Strategy.CanSuspend[true] = Strategy.cede(
      maxRetries = Some(42),
      maxSpin = 999,
      randomizeSpin = false,
      maxCede = 2,
      randomizeCede = false,
    )
    assertEquals(s2.canSuspend, true)
    assertEquals(s2.maxRetries, Some(42))
    assertEquals(s2.maxRetriesInt, 42)
    assertEquals(s2.maxSpin, 999)
    assertEquals(s2.randomizeSpin, false)
    assertEquals(s2.maxCede, 2)
    assertEquals(s2.randomizeCede, false)
    assertEquals(s2.maxSleep, Duration.Zero)
    assertEquals(s2.randomizeSleep, false)

    val s3: Strategy.CanSuspend[true] = Strategy.sleep(
      maxRetries = Some(42),
      maxSpin = 999,
      randomizeSpin = false,
      maxCede = 2,
      randomizeCede = false,
      maxSleep = 1.second,
      randomizeSleep = true,
    )
    assertEquals(s3.canSuspend, true)
    assertEquals(s3.maxRetries, Some(42))
    assertEquals(s3.maxRetriesInt, 42)
    assertEquals(s3.maxSpin, 999)
    assertEquals(s3.randomizeSpin, false)
    assertEquals(s3.maxCede, 2)
    assertEquals(s3.randomizeCede, false)
    assertEquals(s3.maxSleep, 1.second)
    assertEquals(s3.maxSleepNanos, 1.second.toNanos)
    assertEquals(s3.randomizeSleep, true)
  }

  test("RetryStrategy copy") {
    val s1: Strategy.CanSuspend[false] = Strategy.Default
    assertEquals(s1.canSuspend, false)
    val s2: Strategy.CanSuspend[false] = s1.withMaxRetries(Some(42))
    assertEquals(s2.canSuspend, false)
    assertEquals(s2.maxRetries, Some(42))
    assertEquals(s2.maxRetriesInt, 42)
    val s3: Strategy.CanSuspend[false] = s2.withMaxRetries(None).withMaxSpin(999)
    assertEquals(s3.canSuspend, false)
    assertEquals(s3.maxRetries, None)
    assertEquals(s3.maxRetriesInt, -1)
    val s4: Strategy.CanSuspend[false] = s3.withRandomizeSpin(false)
    assertEquals(s4.canSuspend, false)
    assertEquals(s4.maxRetries, None)
    assertEquals(s4.maxRetriesInt, -1)
    assertEquals(s4.randomizeSpin, false)

    val s5: Strategy.CanSuspend[true] = s4.withSleep(1.second)
    assertEquals(s5.canSuspend, true)
    assertEquals(s5.maxRetries, None)
    assertEquals(s5.maxRetriesInt, -1)
    assertEquals(s5.randomizeSpin, false)
    assertEquals(s5.maxSleep, 1.second)
    assertEquals(s5.maxSleepNanos, 1.second.toNanos)
    assertEquals(s5.randomizeSleep, true)
    val s6: Strategy.CanSuspend[true] = s5.withSleep(2.second, false)
    assertEquals(s6.canSuspend, true)
    assertEquals(s6.maxRetries, None)
    assertEquals(s6.maxRetriesInt, -1)
    assertEquals(s6.randomizeSpin, false)
    assertEquals(s6.maxSleep, 2.second)
    assertEquals(s6.maxSleepNanos, 2.second.toNanos)
    assertEquals(s6.randomizeSleep, false)

    val s7: Strategy.CanSuspend[true] = s4.withCede(1)
    assertNotEquals(s7 : Strategy, s4 : Strategy)
    assertEquals(s7.maxRetries, s4.maxRetries)
    assertEquals(s7.maxSpin, s4.maxSpin)
    assertEquals(s7.randomizeSpin, s4.randomizeSpin)
    assertEquals(s7.maxSleep, Duration.Zero)
    assertEquals(s7.randomizeSleep, false)
    assertEquals(s7.withCede(1), s7)
  }

  test("RetryStrategy illegal arguments") {
    assert(Either.catchOnly[IllegalArgumentException](Strategy.spin(
      maxRetries = Some(Int.MaxValue), // this is invalid
      maxSpin = Int.MaxValue, // this is ok
      randomizeSpin = false,
    )).isLeft)
    assert(Either.catchOnly[IllegalArgumentException](Strategy.Default.withMaxSpin(0)).isLeft)
  }

  test("RetryStrategy Show/toString") {
    val s1 = Strategy.spin()
    assertEquals(Show[Strategy].show(s1), "RetryStrategy(maxRetries=∞, spin=..4096⚄)")
    val s2 = s1.withRandomizeSpin(false)
    assertEquals(Show[Strategy].show(s2), "RetryStrategy(maxRetries=∞, spin=..4096)")
    val s3 = s2.withCede
    assertEquals(Show[Strategy].show(s3), "RetryStrategy(maxRetries=∞, spin=..4096, cede=..8⚄, sleep=0)")
    val s4 = s3.withSleep
    assertEquals(s4.toString(), "RetryStrategy(maxRetries=∞, spin=..4096, cede=..8⚄, sleep=..64000000 nanoseconds⚄)")
  }
}
