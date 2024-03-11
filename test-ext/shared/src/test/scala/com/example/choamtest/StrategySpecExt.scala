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

package com.example.choamtest

import scala.concurrent.duration._

import dev.tauri.choam.Rxn.Strategy
import dev.tauri.choam.BaseSpec

final class StrategySpecExt extends BaseSpec {

  test("Public Rxn.Strategy API") {
    val s0: Strategy.Spin = Strategy.Default
    s0.maxRetries : Option[Int]
    s0.maxSpin : Int
    s0.randomizeSpin : Boolean
    assertEquals(s0.maxSleep, Duration.Zero)
    assertEquals(s0.randomizeSleep, false)
    val s1: Strategy.Spin = s0.withMaxRetries(Some(1)).withMaxSpin(56).withRandomizeSpin(true)
    val s2: Strategy = s1.withMaxSleep(1.millis)
    s2.maxRetries : Option[Int]
    s2.maxSpin : Int
    s2.randomizeSpin : Boolean
    assertEquals(s2.maxSleep, 1.millis)
    assertEquals(s2.randomizeSleep, true)
    val s3: Strategy.Spin = Strategy.spin(None, 8, randomizeSpin = false)
    assertEquals(s3.maxSpin, 8)
    val s4: Strategy = Strategy.cede(None, 8, randomizeSpin = false, maxCede = 1, randomizeCede = false)
    assertEquals(s4.maxSpin, 8)
    val s5: Strategy = s1.withMaxCede(2)
    assertEquals(s5.maxRetries, s1.maxRetries)
    assertEquals(s5.maxSpin, s1.maxSpin)
    assertEquals(s5.randomizeSpin, s1.randomizeSpin)
    val s6: Strategy = s5.withMaxCede(0)
    assertEquals(s6, s1)
  }

  test("Turning off things") {
    val s0 = Strategy.sleep(
      maxRetries = None,
      maxSpin = 8,
      randomizeSpin = true,
      maxCede = 8,
      randomizeCede = true,
      maxSleep = 1.minute,
      randomizeSleep = true,
    )
    val s1 = s0.withMaxCede(0).withMaxSleep(0.millis)
    assert(s1.isInstanceOf[Strategy.Spin])
    val s2 = s1.withRandomizeSleep(true)
    assert(!s2.isInstanceOf[Strategy.Spin])
    val s3 = s1.withRandomizeSleep(false)
    assertEquals(s3, s1)
    val s4 = s0.withRandomizeCede(false).withRandomizeSleep(false).withMaxCede(0).withMaxSleep(0.millis)
    assertEquals(s4, s1)
  }
}
