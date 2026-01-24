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

import java.util.concurrent.ThreadLocalRandom
import java.lang.Math.{ min, max }

import scala.concurrent.duration._

import cats.effect.kernel.GenTemporal

/**
 * Utilities for exponential backoff.
 */
private[choam] object Backoff extends BackoffPlatform {

  private[this] final val sleepIncrementNanos =
    1.micros.toNanos // TODO: measure appropriate default; replace method call with const

  final def backoff[F[_]](
    retries: Int,
    maxSpinRetries: Int,
    randomizeSpin: Boolean,
    canSuspend: Boolean,
    maxSleepRetries: Int,
    randomizeSleep: Boolean,
    random: ThreadLocalRandom,
  )(implicit F: GenTemporal[F, ?]): F[Unit] = {
    val tok = backoffTokens(
      retries = retries,
      maxSpinRetries = maxSpinRetries,
      randomizeSpin = randomizeSpin,
      canSuspend = canSuspend,
      maxSleepRetries = maxSleepRetries,
      randomizeSleep = randomizeSleep,
      random = random,
    )
    if (tok > 0L) {
      // positive => spin right now, then return null
      spin(tok.toInt)
      nullOf[F[Unit]]
    } else if (tok == 0L) {
      // zero => cede in F
      F.cede
    } else {
      // negative => sleep in F
      F.sleep((-tok).nanos)
    }
  }

  final def backoffTokens(
    retries: Int,
    maxSpinRetries: Int,
    randomizeSpin: Boolean,
    canSuspend: Boolean,
    maxSleepRetries: Int,
    randomizeSleep: Boolean,
    random: ThreadLocalRandom,
  ): Long = {
    require(retries >= 0)
    require(maxSpinRetries < maxSleepRetries)
    val overSpin = retries - maxSpinRetries
    if ((overSpin <= 0) || (!canSuspend)) {
      // we'll spin
      val normalizedRetries = min(retries, min(maxSpinRetries, 30))
      val spinTarget = 1 << normalizedRetries
      val spinTokens = if (randomizeSpin) random.nextInt(spinTarget) else spinTarget - 1
      max(spinTokens.toLong, 1L) // always call onSpinWait at least once
    } else {
      val cede = maxSleepRetries - maxSpinRetries
      if ((overSpin == 1) || (cede == 1)) {
        // we'll cede
        0L
      } else {
        // we'll sleep
        val sleepRetries = overSpin - 2 // because -1 is cede only
        val normalizedRetries = min(sleepRetries, min(maxSleepRetries - maxSpinRetries - 2, 30))
        val sleepTarget: Long = (1 << normalizedRetries) * sleepIncrementNanos
        val sleepNanos = if (randomizeSleep) random.nextLong(sleepTarget) else sleepTarget - 1L
        -max(sleepNanos, 1L) // always sleep (not just cede)
      }
    }
  }

  /**
   * Truncated exponential backoff.
   *
   * Backoff is implemented with spin-waiting, by calling
   * `Thread.onSpinWait` a number of times. The number of
   * calls starts at 1, doubles for each retry, and is
   * truncated at `maxBackoff`.
   *
   * That is, for retries = 0, 1, 2, 3, ... there will be
   * 1, 2, 4, 8, ... calls to `onSpinWait`.
   *
   * @param retries is the number of retries so far (retries = 0, for
   *                the backoff before the first retry).
   * @param maxBackoff is the maximum number of calls to `onSpinWait`
   *                   to make.
   */
  final def backoffConst(retries: Int, maxBackoff: Int): Unit =
    spin(constTokens(retries, maxBackoff))

  final def sleepConst[F[_]](retries: Int, maxSleepNanos: Long)(
    implicit F: GenTemporal[F, ?]
  ): F[Unit] = {
    val sleepNanos = sleepConstNanos(retries, maxSleepNanos)
    if (sleepNanos > 0L) F.sleep(sleepNanos.nanos)
    else F.cede
  }

  final def sleepConstNanos(retries: Int, maxSleepNanos: Long): Long = {
    require(retries > 0)
    // TODO: since we're using longs, maybe we could use retries > 30
    val tokens: Long = 1L << normalizeRetries(retries)
    val targetSleepNanos: Long = tokens * sleepIncrementNanos
    if (targetSleepNanos > maxSleepNanos) maxSleepNanos else targetSleepNanos
  }

  private[choam] final def constTokens(retries: Int, maxBackoff: Int): Int = {
    require(retries > 0)
    java.lang.Math.min(1 << normalizeRetries(retries), maxBackoff)
  }

  /**
   * Randomized truncated exponential backoff.
   *
   * Backoff is implemented with spin-waiting, by calling
   * `Thread.onSpinWait` a number of times. The number of calls
   * is uniformly random, but at least 1, and its expected value
   * is approximately the number of calls `backoffConst` would
   * make (when called with the same parameters).
   *
   * That is, for retries = 0, 1, 2, ... the number of calls
   * will be in the intervals [1, 2], [1, 4], [1, 8], ...
   *
   * The random value is generated by a thread-local generator,
   * so different threads will likely generate different values.
   *
   * @param retries is the number of retries so far (retries = 0, for
   *                the backoff before the first retry).
   * @param halfMaxBackoff is the half of the maximum number of calls
   *                       to `onSpinWait` to make.
   */
  final def backoffRandom(retries: Int, halfMaxBackoff: Int): Unit =
    backoffRandom(retries, halfMaxBackoff, ThreadLocalRandom.current())

  /**
   * Randomized truncated exponential backoff.
   *
   * Backoff is implemented with spin-waiting, by calling
   * `Thread.onSpinWait` a number of times. The number of calls
   * is uniformly random, but at least 1, and its expected value
   * is approximately the number of calls `backoffConst` would
   * make (when called with the same parameters).
   *
   * That is, for retries = 0, 1, 2, ... the number of calls
   * will be in the intervals [1, 2], [1, 4], [1, 8], ...
   *
   * The random value is generated by a thread-local generator,
   * so different threads will likely generate different values.
   *
   * @param retries is the number of retries so far (retries = 0, for
   *                the backoff before the first retry).
   * @param halfMaxBackoff is the half of the maximum number of calls
   *                       to `onSpinWait` to make.
   * @param random the thread-local rng of the current thread.
   */
  final def backoffRandom(retries: Int, halfMaxBackoff: Int, random: ThreadLocalRandom): Unit =
    spin(randomTokens(retries, halfMaxBackoff, random))

  final def sleepRandom[F[_]](retries: Int, halfMaxSleepNanos: Long, random: ThreadLocalRandom)(
    implicit F: GenTemporal[F, ?]
  ): F[Unit] = {
    val sleepNanos = sleepRandomNanos(retries, halfMaxSleepNanos, random)
    if (sleepNanos > 0L) F.sleep(sleepNanos.nanos)
    else F.cede
  }

  final def sleepRandomNanos(retries: Int, halfMaxSleepNanos: Long, random: ThreadLocalRandom): Long = {
    require(retries > 0)
    val maxNanos: Long = java.lang.Math.min(
      (1 << normalizeRetries(retries + 1)) * sleepIncrementNanos,
      (halfMaxSleepNanos * 2L)
    )
    if (maxNanos < 2L) 1L
    else (1L + random.nextLong(maxNanos))
  }

  private[choam] final def randomTokens(retries: Int, halfMaxBackoff: Int, random: ThreadLocalRandom): Int = {
    require(retries > 0)
    val max = java.lang.Math.min(1 << normalizeRetries(retries + 1), halfMaxBackoff << 1)
    if (max < 2) 1
    else 1 + random.nextInt(max)
  }

  @tailrec
  private[choam] final def spin(n: Int): Unit = {
    if (n > 0) {
      once()
      spin(n - 1)
    }
  }

  /**
   * The greatest "usable" number of retries is 30,
   * since 1 << 31 is negative.
   */
  private[this] final def normalizeRetries(retries: Int): Int = {
    java.lang.Math.min(retries, 30)
  }
}
