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

package com.example.choamtest

import scala.concurrent.duration._

import dev.tauri.choam.{ RetryStrategy => Strategy }
import dev.tauri.choam.BaseSpec

final class StrategySpecExt extends BaseSpec {

  test("Public RetryStrategy API") {
    val s0: Strategy.CanSuspend[false] = Strategy.Default
    s0.maxRetries : Option[Int]
    s0.maxSpin : Int
    s0.randomizeSpin : Boolean
    assertEquals(s0.maxSleep, Duration.Zero)
    assertEquals(s0.randomizeSleep, false)
    val s1: Strategy.CanSuspend[false] = s0.withMaxRetries(Some(1)).withMaxSpin(56).withRandomizeSpin(true)
    val s2: Strategy.CanSuspend[true] = s1.withSleep(1.millis)
    s2.maxRetries : Option[Int]
    s2.maxSpin : Int
    s2.randomizeSpin : Boolean
    assertEquals(s2.maxSleep, 1.millis)
    assertEquals(s2.randomizeSleep, true)
    val s3: Strategy.CanSuspend[false] = Strategy.spin(None, 8, randomizeSpin = false)
    assertEquals(s3.maxSpin, 8)
    val s4: Strategy.CanSuspend[true] = Strategy.cede(None, 8, randomizeSpin = false, maxCede = 1, randomizeCede = false)
    assertEquals(s4.maxSpin, 8)
    val s5: Strategy.CanSuspend[true] = s1.withCede(2)
    assertEquals(s5.maxRetries, s1.maxRetries)
    assertEquals(s5.maxSpin, s1.maxSpin)
    assertEquals(s5.randomizeSpin, s1.randomizeSpin)
  }

  test("withCede/withSleep") {
    val s0: Strategy.CanSuspend[false] = Strategy.spin(maxRetries = None, maxSpin = 32, randomizeSpin = true)
    val s1: Strategy.CanSuspend[true] = s0.withCede
    assertEquals(s1.maxCede, Strategy.cede().maxCede)
    assertEquals(s1.randomizeCede, Strategy.cede().randomizeCede)
    val s3: Strategy.CanSuspend[true] = s1.withSleep
    assertEquals(s3.maxSleep, Strategy.sleep().maxSleep)
    assertEquals(s3.randomizeSleep, Strategy.sleep().randomizeSleep)
    assertEquals(s3.maxCede, s1.maxCede)
  }
}
