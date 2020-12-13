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

import java.util.concurrent.ThreadLocalRandom

// TODO: make thread-local instances, reset
// TODO: and reuse them as needed (to avoid allocations)

/**
 * Truncated exponential backoff.
 *
 * This class is not thread-safe.
 */
private[choam] final class Backoff {

  private[this] var c: Int =
    0

  private[this] var seed: Int = {
    def go(): Int = {
      val r: Int = ThreadLocalRandom.current().nextInt()
      if (r == 0) go()
      else r
    }
    go()
  }

  def backoff(): Unit = {
    val max: Int = {
      if (c < Backoff.max) c += 1
      1 << (c + Backoff.shift)
    }
    val wait: Int = {
      val w: Int = seed % max
      if (w < 0) -w
      else w
    }
    backoffFix(wait)
  }

  def backoffFix(amount: Int): Unit = {
    this.seed = spin(amount, this.seed)
  }

  private def spin(n: Int, s: Int): Int = {
    var cnt: Int = n
    var seed: Int = s
    while ((cnt > 0) || (seed == 1)) {
      seed = xorShift(seed)
      cnt -= 1
    }
    seed
  }

  private def xorShift(s: Int): Int = {
    var seed = s
    seed ^= seed << 1
    seed ^= seed >>> 3
    seed ^= seed << 10
    seed
  }
}

private final object Backoff {
  private final val max: Int =
    Runtime.getRuntime().availableProcessors() + 1 // FIXME
  private final val shift =
    6 // TODO: determine the correct value
}
