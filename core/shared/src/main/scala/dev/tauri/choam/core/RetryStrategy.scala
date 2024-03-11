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

sealed abstract class RetryStrategy extends Product with Serializable {

  // TODO: do we EVER want `randomize*` to be actually false?

  // SPIN:

  // maxRetries:

  def maxRetries: Option[Int]

  def withMaxRetries(maxRetries: Option[Int]): RetryStrategy

  private[core] def maxRetriesInt: Int

  // maxSpin:

  def maxSpin: Int

  def withMaxSpin(maxSpin: Int): RetryStrategy

  // randomizeSpin:

  def randomizeSpin: Boolean

  def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy

  // CEDE:

  def withCede(cede: Boolean): RetryStrategy

  // maxCede:

  def maxCede: Int

  def withMaxCede(maxCede: Int): RetryStrategy

  // randomizeCede:

  def randomizeCede: Boolean

  def withRandomizeCede(randomizeCede: Boolean): RetryStrategy

  // SLEEP:

  def withSleep(sleep: Boolean): RetryStrategy

  // maxSleep:

  def maxSleep: FiniteDuration

  private[core] def maxSleepNanos: Long

  def withMaxSleep(maxSleep: FiniteDuration): RetryStrategy

  // randomizeSleep:

  def randomizeSleep: Boolean

  def withRandomizeSleep(randomizeSleep: Boolean): RetryStrategy

  // MISC.:

  private[core] def canSuspend: Boolean
}

object RetryStrategy {

  sealed abstract class Spin
    extends RetryStrategy {

    override def withMaxRetries(maxRetries: Option[Int]): Spin

    override def withMaxSpin(maxSpin: Int): Spin

    override def withRandomizeSpin(randomizeSpin: Boolean): Spin

    private[core] final override def canSuspend: Boolean =
      false
  }

  private final case class StrategyFull(
    maxRetries: Option[Int],
    maxSpin: Int,
    randomizeSpin: Boolean,
    maxCede: Int,
    randomizeCede: Boolean,
    maxSleep: FiniteDuration,
    randomizeSleep: Boolean,
  ) extends RetryStrategy {

    require(maxRetries.forall{ mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
    require(maxSpin > 0)
    require(maxCede >= 0)
    require(!((maxCede == 0) && randomizeCede))
    require(maxSleep >= Duration.Zero)
    require(!((maxSleep == Duration.Zero) && randomizeSleep))
    require((maxCede > 0) || (maxSleep > Duration.Zero)) // otherwise it should be SPIN

    final override def withMaxRetries(maxRetries: Option[Int]): RetryStrategy = {
      if (maxRetries == this.maxRetries) this
      else this.copy(maxRetries = maxRetries)
    }

    final override def withMaxSpin(maxSpin: Int): RetryStrategy = {
      if (maxSpin == this.maxSpin) this
      else this.copy(maxSpin = maxSpin)
    }

    final override def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy = {
      if (randomizeSpin == this.randomizeSpin) this
      else this.copy(randomizeSpin = randomizeSpin)
    }

    final override def withCede(cede: Boolean): RetryStrategy = {
      if (cede) {
        if (this.maxCede == 0) {
          this
            .withMaxCede(BackoffPlatform.maxCedeDefault)
            .withRandomizeCede(BackoffPlatform.randomizeCedeDefault)
        } else {
          this
        }
      } else {
        this.withMaxCede(0).withRandomizeCede(false)
      }
    }

    final override def withMaxCede(maxCede: Int): RetryStrategy = {
      if ((maxCede == 0) && (this.maxSleepNanos == 0L)) {
        StrategySpin(
          maxRetries = this.maxRetries,
          maxSpin = this.maxSpin,
          randomizeSpin = this.randomizeSpin,
        )
      } else if (maxCede == this.maxCede) {
        this
      } else if (maxCede == 0) {
        this.copy(maxCede = 0, randomizeCede = false)
      } else {
        this.copy(maxCede = maxCede)
      }
    }

    final override def withRandomizeCede(randomizeCede: Boolean): RetryStrategy = {
      if (randomizeCede == this.randomizeCede) {
        this
      } else if (randomizeCede && (this.maxCede == 0)) {
        this.copy(randomizeCede = true, maxCede = BackoffPlatform.maxCedeDefault)
      } else {
        this.copy(randomizeCede = randomizeCede)
      }
    }

    final override def withSleep(sleep: Boolean): RetryStrategy = {
      if (sleep) {
        if (this.maxSleepNanos == 0L) {
          this
            .withMaxSleep(BackoffPlatform.maxSleepDefaultDuration)
            .withRandomizeSleep(BackoffPlatform.randomizeSleepDefault)
        } else {
          this
        }
      } else {
        this.withMaxSleep(Duration.Zero).withRandomizeSleep(false)
      }
    }

    final override def withMaxSleep(maxSleep: FiniteDuration): RetryStrategy = {
      if ((maxSleep == Duration.Zero) && (this.maxCede == 0)) {
        StrategySpin(
          maxRetries = this.maxRetries,
          maxSpin = this.maxSpin,
          randomizeSpin = this.randomizeSpin,
        )
      } else if (maxSleep == this.maxSleep) {
        this
      } else if (maxSleep == Duration.Zero) {
        this.copy(maxSleep = maxSleep, randomizeSleep = false)
      } else {
        this.copy(maxSleep = maxSleep)
      }
    }

    final override def withRandomizeSleep(randomizeSleep: Boolean): RetryStrategy = {
      if (randomizeSleep == this.randomizeSleep) {
        this
      } else if (randomizeSleep && (this.maxSleepNanos == 0L)) {
        this.copy(randomizeSleep = true, maxSleep = BackoffPlatform.maxSleepDefaultDuration)
      } else {
        this.copy(randomizeSleep = randomizeSleep)
      }
    }

    private[core] override val maxRetriesInt: Int = maxRetries match {
      case Some(n) => n
      case None => -1
    }

    private[core] override val maxSleepNanos: Long =
      maxSleep.toNanos

    private[core] final override def canSuspend: Boolean =
      true
  }

  private final case class StrategySpin(
    maxRetries: Option[Int],
    maxSpin: Int,
    randomizeSpin: Boolean,
  ) extends Spin {

    require(maxRetries.forall{ mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
    require(maxSpin > 0)

    final override def withMaxRetries(maxRetries: Option[Int]): Spin = {
      if (maxRetries == this.maxRetries) this
      else this.copy(maxRetries = maxRetries)
    }

    final override def withMaxSpin(maxSpin: Int): Spin = {
      if (maxSpin == this.maxSpin) this
      else this.copy(maxSpin = maxSpin)
    }

    final override def withRandomizeSpin(randomizeSpin: Boolean): Spin = {
      if (randomizeSpin == this.randomizeSpin) this
      else this.copy(randomizeSpin = randomizeSpin)
    }

    final override def withCede(cede: Boolean): RetryStrategy = {
      if (cede) {
        this
          .withMaxCede(BackoffPlatform.maxCedeDefault)
          .withRandomizeCede(BackoffPlatform.randomizeCedeDefault)
      } else {
        this
      }
    }

    final override def withMaxCede(maxCede: Int): RetryStrategy = {
      if (maxCede == 0) {
        this
      } else {
        StrategyFull(
          maxRetries = maxRetries,
          maxSpin = maxSpin,
          randomizeSpin = randomizeSpin,
          maxCede = maxCede,
          randomizeCede = BackoffPlatform.randomizeCedeDefault,
          maxSleep = Duration.Zero,
          randomizeSleep = false,
        )
      }
    }

    final override def withRandomizeCede(randomizeCede: Boolean): RetryStrategy = {
      if (randomizeCede) {
        StrategyFull(
          maxRetries = maxRetries,
          maxSpin = maxSpin,
          randomizeSpin = randomizeSpin,
          maxCede = BackoffPlatform.maxCedeDefault,
          randomizeCede = true,
          maxSleep = Duration.Zero,
          randomizeSleep = false,
        )
      } else {
        this
      }
    }

    final override def withSleep(sleep: Boolean): RetryStrategy = {
      if (sleep) {
        this
          .withMaxSleep(BackoffPlatform.maxSleepDefaultDuration)
          .withRandomizeSleep(BackoffPlatform.randomizeSleepDefault)
      } else {
        this
      }
    }

    final override def withMaxSleep(maxSleep: FiniteDuration): RetryStrategy = {
      if (maxSleep == Duration.Zero) {
        this
      } else {
        StrategyFull(
          maxRetries = maxRetries,
          maxSpin = maxSpin,
          randomizeSpin = randomizeSpin,
          maxCede = BackoffPlatform.maxCedeDefault, // TODO: 0?
          randomizeCede = BackoffPlatform.randomizeCedeDefault, // TODO: false?
          maxSleep = maxSleep,
          randomizeSleep = BackoffPlatform.randomizeSleepDefault,
        )
      }
    }

    final override def withRandomizeSleep(randomizeSleep: Boolean): RetryStrategy = {
      if (randomizeSleep) {
        StrategyFull(
          maxRetries = maxRetries,
          maxSpin = maxSpin,
          randomizeSpin = randomizeSpin,
          maxCede = BackoffPlatform.maxCedeDefault, // TODO: 0?
          randomizeCede = BackoffPlatform.randomizeCedeDefault, // TODO: false?
          maxSleep = BackoffPlatform.maxSleepDefaultDuration,
          randomizeSleep = true,
        )
      } else {
        this
      }
    }

    private[core] override val maxRetriesInt: Int = maxRetries match {
      case Some(n) => n
      case None => -1
    }

    final override def maxCede: Int =
      0

    final override def randomizeCede: Boolean =
      false

    final override def maxSleep: FiniteDuration =
      Duration.Zero

    private[core] final override def maxSleepNanos: Long =
      0L

    final override def randomizeSleep: Boolean =
      false
  }

  final val Default: Spin =
    this.spin()

  final def spin(
    maxRetries: Option[Int] = None,
    maxSpin: Int = BackoffPlatform.maxPauseDefault,
    randomizeSpin: Boolean = BackoffPlatform.randomizePauseDefault,
  ): Spin = {
    StrategySpin(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
    )
  }

  final def cede(
    maxRetries: Option[Int] = None,
    maxSpin: Int = BackoffPlatform.maxPauseDefault,
    randomizeSpin: Boolean = BackoffPlatform.randomizePauseDefault,
    maxCede: Int = BackoffPlatform.maxCedeDefault,
    randomizeCede: Boolean = BackoffPlatform.randomizeCedeDefault,
  ): RetryStrategy = {
    StrategyFull(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
      maxCede = maxCede,
      randomizeCede = randomizeCede,
      maxSleep = Duration.Zero,
      randomizeSleep = false,
    )
  }

  final def sleep(
    maxRetries: Option[Int] = None,
    maxSpin: Int = BackoffPlatform.maxPauseDefault,
    randomizeSpin: Boolean = BackoffPlatform.randomizePauseDefault,
    maxCede: Int = BackoffPlatform.maxCedeDefault,
    randomizeCede: Boolean = BackoffPlatform.randomizeCedeDefault,
    maxSleep: FiniteDuration = BackoffPlatform.maxSleepDefaultDuration,
    randomizeSleep: Boolean = BackoffPlatform.randomizeSleepDefault,
  ): RetryStrategy = {
    require(maxSleep > Duration.Zero)
    StrategyFull(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
      maxCede = maxCede,
      randomizeCede = randomizeCede,
      maxSleep = maxSleep,
      randomizeSleep = randomizeSleep,
    )
  }
}
