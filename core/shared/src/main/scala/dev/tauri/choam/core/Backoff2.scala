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

import cats.effect.kernel.GenTemporal

private object Backoff2 extends Backoff2

private abstract class Backoff2 extends BackoffPlatform {

  // - We first start PAUSEing: 1, 2, 4, 8, ... 4096 (but user configurable).
  //   (Then we don't any more, because 10000 PAUSE ≅ 1 CEDE, and we do
  //   repeated calls, so we stop well before 10000.)
  // - So then we start CEDEing: 1, 2, 4, 8 (but user configurable).
  //   (Then we don't any more, because 25 CEDE ≅ 1 SLEEP = 8ms, and we do
  //   repeated calls as above.)
  // - So then we start SLEEPing: 1=8ms, 2, 4, 8 (but user configurable).
  // Note: with randomization, these are maximum values.

  // These values are very rough estimates based
  // on the following (rough) measurements:    estimate:             multiplier:
  // onSpinWait() is approx.  14..40ns   -->   30ns  =       30ns    1×PAUSE
  // IO.cede is approx.       0.2..1.5ms -->   0.3ms =   300000ns    10000×PAUSE
  // IO.sleep(1ns) is approx. 15ms       -->   8ms  = 8000000ns      26.66×CEDE ≅ 25×CEDE

  /**
   * We sleep whole multiples of this.
   */
  protected[this] final val sleepAtom: FiniteDuration =
    8000000L.nanos // 8ms

  final def backoffStrTok(
    retries: Int,
    strategy: RetryStrategy,
    canSuspend: Boolean, // if `false`, overrides `strategy.canSuspend`
  ): Long = {
    val canReallySuspend = canSuspend && strategy.canSuspend
    this.backoffTok(
      retries = if (retries > 30) 30 else retries,
      maxPause = strategy.maxSpin,
      randomizePause = strategy.randomizeSpin,
      maxCede = if (canReallySuspend) strategy.maxCede else 0,
      randomizeCede = strategy.randomizeCede,
      maxSleep = if (canReallySuspend) {
        java.lang.Math.toIntExact(strategy.maxSleep.toNanos >> BackoffPlatform.sleepAtomShiftNs)
      } else {
        0
      },
      randomizeSleep = strategy.randomizeSleep,
    )
  }

  final def tokenToF[F[_]](token: Long)(implicit F: GenTemporal[F, _]): F[Unit] = {
    val mark = token & (~BackoffPlatform.backoffTokenMask)
    val k = (token & BackoffPlatform.backoffTokenMask).toInt
    if (mark == BackoffPlatform.backoffSpinMark) {
      F.unit
    } else if (mark == BackoffPlatform.backoffCedeMark) {
      if (k == 1) {
        F.cede
      } else {
        F.replicateA_(k, F.cede)
      }
    } else if (mark == BackoffPlatform.backoffSleepMark) {
      F.sleep(k * sleepAtom)
    } else {
      throw new IllegalArgumentException(token.toString)
    }
  }

  final def isPauseToken(token: Long): Boolean = {
    val mark = token & (~BackoffPlatform.backoffTokenMask)
    (mark == BackoffPlatform.backoffSpinMark)
  }

  final def spinIfPauseToken(token: Long): Boolean = {
    if (isPauseToken(token)) {
      val k = (token & BackoffPlatform.backoffTokenMask).toInt
      spin(k)
      true
    } else {
      false
    }
  }

  /**
   * Returns a backoff token.
   *
   * @see tokenToF to easily convert the returned token to
   *      a `F[Unit]`.
   */
  final def backoffTok(
    retries: Int,
    maxPause: Int = BackoffPlatform.maxPauseDefault,
    randomizePause: Boolean = true,
    maxCede: Int = BackoffPlatform.maxCedeDefault,
    randomizeCede: Boolean = true,
    maxSleep: Int = BackoffPlatform.maxSleepDefault,
    randomizeSleep: Boolean = true,
  ): Long = {
    require(retries > 0)
    require(retries <= 30) // TODO: do we need to support bigger values?
    require(maxPause > 0)
    require(maxCede >= 0)
    require(maxSleep >= 0)

    // TODO: we could probably simplify this code:
    val ro = retries - 1
    val pauseUntil = log2floor(maxPause) // maxPause > 0 always
    if (ro <= pauseUntil) {
      // we'll PAUSE
      pause(1 << ro, randomizePause)
    } else if (maxCede == 0) {
      // we'll PAUSE (we're not allowed to cede)
      pause(maxPause, randomizePause)
      // TODO: in this case, we could use a larger `maxPause`
    } else {
      val roo = (ro - pauseUntil) - 1
      val cedeUntil = log2floor(maxCede) // maxCede > 0 here
      if (roo <= cedeUntil) {
        // we'll CEDE
        cede(1 << roo, randomizeCede)
      } else if (maxSleep == 0) {
        // we'll CEDE (we're not allowed to sleep)
        cede(maxCede, randomizeCede)
      } else {
        val rooo = (roo - cedeUntil) - 1
        val sleepUntil = log2floor(maxSleep) // maxSleep > 0 here
        // we'll SLEEP
        if (rooo <= sleepUntil) {
          sleep(1 << rooo, randomizeSleep)
        } else {
          sleep(maxSleep, randomizeSleep)
        }
      }
    }
  }

  private[this] final def pause(n: Int, randomize: Boolean): Long = {
    val k = if (randomize) rnd(n) else n
    BackoffPlatform.backoffSpinMark | k.toLong
  }

  @tailrec
  private[this] final def spin(n: Int): Unit = {
    if (n > 0) {
      once()
      spin(n - 1)
    }
  }

  @inline
  private[this] final def cede(n: Int, randomize: Boolean): Long = {
    val k = if (randomize) rnd(n) else n
    BackoffPlatform.backoffCedeMark | k.toLong
  }

  @inline
  private[this] final def sleep(n: Int, randomize: Boolean): Long = {
    val k = if (randomize) rnd(n) else n
    BackoffPlatform.backoffSleepMark | k.toLong
  }

  @inline
  private[this] final def rnd(n: Int): Int = {
    ThreadLocalRandom.current().nextInt(n + 1)
  }

  /**
   * log₂x rounded down
   *
   * From Hacker's Delight by Henry S. Warren, Jr. (section 11–4)
   */
  @inline
  private[this] final def log2floor(x: Int): Int = {
    31 - Integer.numberOfLeadingZeros(x)
  }

  /** For testing */
  final def log2floor_testing(x: Int): Int =
    log2floor(x)
}
