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

import cats.syntax.all._
import cats.effect.kernel.GenTemporal

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

  private[this] final val sleepAtomShiftNs =
    23 // FIXME

  private[this] final val maxPauseD = // default
    4096

  private[this] final val maxCedeD = // default
    8

  private[this] final val maxSleepD = // default
    8 // 8×8ms = 64ms

  // These are abstract to ease testing:

  protected[this] def pause[F[_]](n: Int, randomize: Boolean)(implicit F: GenTemporal[F, _]): F[Unit]

  protected[this] def cede[F[_]](n: Int, randomize: Boolean)(implicit F: GenTemporal[F, _]): F[Unit]

  protected[this] def sleep[F[_]](n: Int, randomize: Boolean)(implicit F: GenTemporal[F, _]): F[Unit]

  final def backoffStr[F[_]](retries: Int, strategy: Rxn.Strategy)(implicit F: GenTemporal[F, _]): F[Unit] = {
    val maxSl = strategy.maxSleep.toNanos >> sleepAtomShiftNs
    this.backoff(
      retries = if (retries > 30) 30 else retries,
      maxPause = strategy.maxSpin,
      randomizePause = strategy.randomizeSpin,
      maxCede = strategy.maxCede,
      randomizeCede = strategy.randomizeCede,
      maxSleep = java.lang.Math.toIntExact(maxSl),
      randomizeSleep = strategy.randomizeSleep,
    )
  }

  final def backoff[F[_]](
    retries: Int,
    maxPause: Int = maxPauseD,
    randomizePause: Boolean = true,
    maxCede: Int = maxCedeD,
    randomizeCede: Boolean = true,
    maxSleep: Int = maxSleepD,
    randomizeSleep: Boolean = true,
  )(implicit F: GenTemporal[F, _]): F[Unit] = {
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

private object Backoff2 extends Backoff2 {

  protected[this] final override def pause[F[_]](n: Int, randomize: Boolean)(implicit F: GenTemporal[F, _]): F[Unit] = {
    // spin right now, then return null
    val k = if (randomize) rnd(n) else n
    spin(k)
    nullOf[F[Unit]]
  }

  @tailrec
  private[this] final def spin(n: Int): Unit = {
    if (n > 0) {
      once()
      spin(n - 1)
    }
  }

  protected[this] final override def cede[F[_]](n: Int, randomize: Boolean)(implicit F: GenTemporal[F, _]): F[Unit] = {
    val k = if (randomize) rnd(n) else n
    if (k == 1) {
      F.cede
    } else {
      F.cede.replicateA_(k)
    }
  }

  protected[this] final override def sleep[F[_]](n: Int, randomize: Boolean)(implicit F: GenTemporal[F, _]): F[Unit] = {
    val k = if (randomize) rnd(n) else n
    F.sleep(k * sleepAtom)
  }

  @inline
  private[this] final def rnd(n: Int): Int = {
    ThreadLocalRandom.current().nextInt(n + 1)
  }
}
