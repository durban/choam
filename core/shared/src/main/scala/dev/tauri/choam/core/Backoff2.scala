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

import cats.syntax.all._
import cats.effect.kernel.GenTemporal

private abstract class Backoff2 extends BackoffPlatform {

  // - We first start PAUSEing: 1, 2, 4, 8, ... 2048 (but user configurable).
  //   (Then we don't any more, because 4000 PAUSE ≅ 1 CEDE)
  // - So then we start CEDEing: 1, 2, 4, 8, ... 32 (but user configurable).
  //   (Then we don't any more, because 40 CEDE ≅ 1 SLEEP = 8ms)
  // - So then we start SLEEPing: 1=8ms, 2, 4, 8, ... ⩽ maxSleep (user defined).
  // Note: with randomization, these are maximum values.

  /**
   * We sleep whole multiples of this.
   */
  protected[this] final val sleepAtom: FiniteDuration =
    8.milliseconds

  private[this] final val maxPauseD = // default
    2048

  private[this] final val maxCedeD = // default
    32

  private[this] final val maxSleepD = // default
    8 // 64ms

  // These are abstract to ease testing:

  protected[this] def pause[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit]

  protected[this] def cede[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit]

  protected[this] def sleep[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit]

  // TODO: add randomization
  final def backoff[F[_]](
    retries: Int,
    maxPause: Int = maxPauseD,
    maxCede: Int = maxCedeD,
    maxSleep: Int = maxSleepD,
  )(implicit F: GenTemporal[F, _]): F[Unit] = {
    require(retries >= 0)
    require(retries <= 30)
    require(maxPause > 0)
    require(maxCede >= 0)
    require(maxSleep >= 0)

    // TODO: we could probably simplify this code:
    val pauseUntil = log2ceil(maxPause)
    if (retries <= pauseUntil) {
      // we'll PAUSE
      pause(1 << retries)
    } else if (maxCede == 0) {
      // we'll PAUSE (we're not allowed to cede)
      pause(maxPause)
      // TODO: in this case, we could use a larger `maxPause`
    } else {
      val retriesOver = (retries - pauseUntil) - 1
      val cedeUntil = log2ceil(maxCede) // maxCede > 0 here
      if (retriesOver <= cedeUntil) {
        // we'll CEDE
        cede(1 << retriesOver)
      } else if (maxSleep == 0) {
        // we'll CEDE (we're not allowed to sleep)
        cede(maxCede)
      } else {
        val retriesOverOver = (retriesOver - cedeUntil) - 1
        val sleepUntil = log2ceil(maxSleep) // maxSleep > 0 here
        // we'll SLEEP
        if (retriesOverOver <= sleepUntil) {
          sleep(1 << retriesOverOver)
        } else {
          sleep(maxSleep)
        }
      }
    }
  }

  /**
   * log₂x rounded up
   *
   * From https://stackoverflow.com/a/51351885
   */
  @inline
  private[this] final def log2ceil(x: Int): Int = {
    assert(x > 0) // otherwise incorrect
    log2floor(x - 1) + 1
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

  /** For testing */
  final def log2ceil_testing(x: Int): Int =
    log2ceil(x)
}

private object Backoff2 extends Backoff2 {

  protected[this] final override def pause[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit] = {
    // spin right now, then return null
    spin(n)
    nullOf[F[Unit]]
  }

  @tailrec
  private[this] final def spin(n: Int): Unit = {
    if (n > 0) {
      once()
      spin(n - 1)
    }
  }

  protected[this] final override def cede[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit] = {
    F.cede.replicateA_(n)
  }

  protected[this] final override def sleep[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit] = {
    F.sleep(n * sleepAtom)
  }
}
