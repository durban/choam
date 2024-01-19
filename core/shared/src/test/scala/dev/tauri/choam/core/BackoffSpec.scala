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

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

class BackoffSpec extends BaseSpec {

  private def backoffTokens(
    retries: Int,
    maxSpinR: Int,
    maxSleepR: Int,
    canSuspend: Boolean = true,
  ): Long = {
    val result = Backoff.backoffTokens(
      retries = retries,
      maxSpinRetries = maxSpinR,
      randomizeSpin = false,
      canSuspend = canSuspend,
      maxSleepRetries = maxSleepR,
      randomizeSleep = false,
      random = ThreadLocalRandom.current()
    )
    if (result > 0L) {
      assertEquals(result.toInt.toLong, result)
    }
    result
  }

  test("Backoff.backoffTokens") {
    // 1.:
    assertEquals(backoffTokens(0, maxSpinR = 3, maxSleepR = 7), 1L)
    assertEquals(backoffTokens(1, maxSpinR = 3, maxSleepR = 7), 1L)
    assertEquals(backoffTokens(2, maxSpinR = 3, maxSleepR = 7), 3L)
    assertEquals(backoffTokens(3, maxSpinR = 3, maxSleepR = 7), 7L)
    assertEquals(backoffTokens(4, maxSpinR = 3, maxSleepR = 7), 0L)
    assertEquals(backoffTokens(5, maxSpinR = 3, maxSleepR = 7), -999L)
    assertEquals(backoffTokens(6, maxSpinR = 3, maxSleepR = 7), -1999L)
    assertEquals(backoffTokens(7, maxSpinR = 3, maxSleepR = 7), -3999L)
    assertEquals(backoffTokens(8, maxSpinR = 3, maxSleepR = 7), -3999L)
    assertEquals(backoffTokens(9, maxSpinR = 3, maxSleepR = 7), -3999L)
    // 2.:
    assertEquals(backoffTokens(0, maxSpinR = 3, maxSleepR = 7, canSuspend = false), 1L)
    assertEquals(backoffTokens(1, maxSpinR = 3, maxSleepR = 7, canSuspend = false), 1L)
    assertEquals(backoffTokens(2, maxSpinR = 3, maxSleepR = 7, canSuspend = false), 3L)
    assertEquals(backoffTokens(3, maxSpinR = 3, maxSleepR = 7, canSuspend = false), 7L)
    assertEquals(backoffTokens(4, maxSpinR = 3, maxSleepR = 7, canSuspend = false), 7L)
    assertEquals(backoffTokens(5, maxSpinR = 3, maxSleepR = 7, canSuspend = false), 7L)
    assertEquals(backoffTokens(4, maxSpinR = 5, maxSleepR = 7, canSuspend = false), 15L)
    assertEquals(backoffTokens(5, maxSpinR = 5, maxSleepR = 7, canSuspend = false), 31L)
    assertEquals(backoffTokens(6, maxSpinR = 5, maxSleepR = 7, canSuspend = false), 31L)
    assertEquals(backoffTokens(7, maxSpinR = 5, maxSleepR = 7, canSuspend = false), 31L)
    assertEquals(backoffTokens(8, maxSpinR = 5, maxSleepR = 7, canSuspend = false), 31L)
    // 3.:
    assertEquals(backoffTokens(0, maxSpinR = 3, maxSleepR = 4), 1L)
    assertEquals(backoffTokens(1, maxSpinR = 3, maxSleepR = 4), 1L)
    assertEquals(backoffTokens(2, maxSpinR = 3, maxSleepR = 4), 3L)
    assertEquals(backoffTokens(3, maxSpinR = 3, maxSleepR = 4), 7L)
    assertEquals(backoffTokens(4, maxSpinR = 3, maxSleepR = 4), 0L)
    assertEquals(backoffTokens(5, maxSpinR = 3, maxSleepR = 4), 0L)
    assertEquals(backoffTokens(6, maxSpinR = 3, maxSleepR = 4), 0L)
    // 4.:
    assertEquals(backoffTokens(0, maxSpinR = 3, maxSleepR = 5), 1L)
    assertEquals(backoffTokens(1, maxSpinR = 3, maxSleepR = 5), 1L)
    assertEquals(backoffTokens(2, maxSpinR = 3, maxSleepR = 5), 3L)
    assertEquals(backoffTokens(3, maxSpinR = 3, maxSleepR = 5), 7L)
    assertEquals(backoffTokens(4, maxSpinR = 3, maxSleepR = 5), 0L)
    assertEquals(backoffTokens(5, maxSpinR = 3, maxSleepR = 5), -999L)
    assertEquals(backoffTokens(6, maxSpinR = 3, maxSleepR = 5), -999L)
    // 5.:
    assertEquals(backoffTokens(0, maxSpinR = 3, maxSleepR = 6), 1L)
    assertEquals(backoffTokens(1, maxSpinR = 3, maxSleepR = 6), 1L)
    assertEquals(backoffTokens(2, maxSpinR = 3, maxSleepR = 6), 3L)
    assertEquals(backoffTokens(3, maxSpinR = 3, maxSleepR = 6), 7L)
    assertEquals(backoffTokens(4, maxSpinR = 3, maxSleepR = 6), 0L)
    assertEquals(backoffTokens(5, maxSpinR = 3, maxSleepR = 6), -999L)
    assertEquals(backoffTokens(6, maxSpinR = 3, maxSleepR = 6), -1999L)
    assertEquals(backoffTokens(7, maxSpinR = 3, maxSleepR = 6), -1999L)
  }

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
    assertEquals(Backoff.sleepConstNanos(retries = 0, maxSleepNanos = 1.second.toNanos), 1.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 0, maxSleepNanos = Long.MaxValue), 1.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 1, maxSleepNanos = 1.second.toNanos), 2.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 2, maxSleepNanos = 1.second.toNanos), 4.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 4, maxSleepNanos = 1.second.toNanos), 16.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 5, maxSleepNanos = 16.micros.toNanos), 16.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 5, maxSleepNanos = 8.micros.toNanos), 8.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 5, maxSleepNanos = Long.MaxValue), 32.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 1024*1024, maxSleepNanos = 32.micros.toNanos), 32.micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 1024*1024, maxSleepNanos = Long.MaxValue), (1 << 30).micros.toNanos)
    assertEquals(Backoff.sleepConstNanos(retries = 1024*1024 + 1, maxSleepNanos = Long.MaxValue), (1 << 30).micros.toNanos)
    // illegal arguments:
    assert(Backoff.sleepConstNanos(retries = -1, maxSleepNanos = 16.micros.toNanos) <= 0L)
    assert(Backoff.sleepConstNanos(retries = -2, maxSleepNanos = 16.micros.toNanos) <= 0L)
    assert(Backoff.sleepConstNanos(retries = 0, maxSleepNanos = -16.micros.toNanos) <= 0L)
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
