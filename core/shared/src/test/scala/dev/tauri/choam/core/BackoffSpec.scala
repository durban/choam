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
package core

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

class BackoffSpec extends BaseSpec {

  test("Backoff.backoffConst") {
    assertEquals(Backoff.constTokens(retries = 0, maxBackoff = 16), 1)
    assertEquals(Backoff.constTokens(retries = 1, maxBackoff = 16), 2)
    assertEquals(Backoff.constTokens(retries = 2, maxBackoff = 16), 4)
    assertEquals(Backoff.constTokens(retries = 4, maxBackoff = 16), 16)
    assertEquals(Backoff.constTokens(retries = 5, maxBackoff = 16), 16)
    assertEquals(Backoff.constTokens(retries = 5, maxBackoff = 8), 8)
    assertEquals(Backoff.constTokens(retries = 1024*1024, maxBackoff = 32), 32)
    // illegal arguments:
    assert(Backoff.constTokens(retries = -1, maxBackoff = 16) < 0)
    assert(Backoff.constTokens(retries = 0, maxBackoff = -32) < 0)
  }

  test("Backoff.sleepConst") {
    assertEquals(Backoff.sleepConst(retries = 0, maxSleep = 1.second), 1.micros)
    assertEquals(Backoff.sleepConst(retries = 0, maxSleep = Duration.Inf), 1.micros)
    assertEquals(Backoff.sleepConst(retries = 1, maxSleep = 1.second), 2.micros)
    assertEquals(Backoff.sleepConst(retries = 2, maxSleep = 1.second), 4.micros)
    assertEquals(Backoff.sleepConst(retries = 4, maxSleep = 1.second), 16.micros)
    assertEquals(Backoff.sleepConst(retries = 5, maxSleep = 16.micros), 16.micros)
    assertEquals(Backoff.sleepConst(retries = 5, maxSleep = 8.micros), 8.micros)
    assertEquals(Backoff.sleepConst(retries = 5, maxSleep = Duration.Inf), 32.micros)
    assertEquals(Backoff.sleepConst(retries = 1024*1024, maxSleep = 32.micros), 32.micros)
    assertEquals(Backoff.sleepConst(retries = 1024*1024, maxSleep = Duration.Inf), (1 << 30).micros)
    assertEquals(Backoff.sleepConst(retries = 1024*1024 + 1, maxSleep = Duration.Inf), (1 << 30).micros)
    // illegal arguments:
    assert(Backoff.sleepConst(retries = -1, maxSleep = 16.micros) < Duration.Zero)
    assert(Backoff.sleepConst(retries = 0, maxSleep = -16.micros) < Duration.Zero)
  }

  test("Backoff.backoffRandom") {
    val nSamples = 100000
    def check(retries: Int, maxBackoff: Int, expMaxTokens: Int): Unit = {
      val expAvg = Backoff.constTokens(retries, maxBackoff)
      val samples = List.fill(nSamples) {
        Backoff.randomTokens(retries, maxBackoff, ThreadLocalRandom.current())
      }
      samples.foreach { sample =>
        assert(clue(sample) <= (clue(maxBackoff) * 2))
        assert(clue(sample) <= clue(expMaxTokens))
        assert(clue(sample) >= 1)
      }
      val avg = samples.sum.toDouble / samples.length.toDouble
      assert(Math.abs(clue(avg) - clue(expAvg)) <= 1.0)
    }
    check(retries = 0, maxBackoff = 16, expMaxTokens = 2)
    check(retries = 1, maxBackoff = 16, expMaxTokens = 4)
    check(retries = 2, maxBackoff = 16, expMaxTokens = 8)
    check(retries = 3, maxBackoff = 16, expMaxTokens = 16)
    check(retries = 4, maxBackoff = 16, expMaxTokens = 32)
    check(retries = 4, maxBackoff = 8, expMaxTokens = 16)
    check(retries = 1024*1024, maxBackoff = 8, expMaxTokens = 16)
    // illegal arguments:
    Backoff.randomTokens(retries = -1, halfMaxBackoff = 16, random = ThreadLocalRandom.current())
    Backoff.randomTokens(retries = -1024*1024, halfMaxBackoff = 16, random = ThreadLocalRandom.current())
  }

  test("Backoff.spin") {
    Backoff.spin(0)
    Backoff.spin(1)
    Backoff.spin(1024)
    Backoff.spin(-1)
    Backoff.spin(-1024)
    Backoff.spin(Int.MinValue)
  }
}
