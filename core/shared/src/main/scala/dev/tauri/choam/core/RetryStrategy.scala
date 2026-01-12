/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
import scala.util.hashing.MurmurHash3

import cats.{ ~>, Hash, Show }
import cats.syntax.all._
import cats.effect.kernel.{ Async, Ref, Deferred }

// TODO:0.5: move to dev.tauri.choam.RetryStrategy
sealed abstract class RetryStrategy {

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

  private[choam] def canSuspend: Boolean

  private[core] def isDebug: Boolean
}

object RetryStrategy {

  sealed abstract class Spin
    extends RetryStrategy {

    override def withMaxRetries(maxRetries: Option[Int]): Spin

    override def withMaxSpin(maxSpin: Int): Spin

    override def withRandomizeSpin(randomizeSpin: Boolean): Spin

    private[choam] final override def canSuspend: Boolean =
      false

    private[core] final override def isDebug: Boolean =
      false
  }

  implicit val showForRetryStrategy: Show[RetryStrategy] = {
    // these have proper `toString`:
    case full: StrategyFull => full.toString
    case spin: StrategySpin => spin.toString
    // this one doesn't:
    case _: Internal.Stepper[_] => "Stepper()"
  }

  implicit val hashForRetryStrategy: Hash[RetryStrategy] = {
    Hash.fromUniversalHashCode[RetryStrategy]
  }

  private[core] final class StrategyFull private (
    override val maxRetries: Option[Int],
    override val maxSpin: Int,
    override val randomizeSpin: Boolean,
    override val maxCede: Int,
    override val randomizeCede: Boolean,
    override val maxSleep: FiniteDuration,
    override val randomizeSleep: Boolean,
  ) extends RetryStrategy {

    require(maxRetries.forall{ mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
    require(maxSpin > 0)
    require(maxCede >= 0)
    require(!((maxCede == 0) && randomizeCede))
    require(maxSleep >= Duration.Zero)
    require(!((maxSleep == Duration.Zero) && randomizeSleep))
    require((maxCede > 0) || (maxSleep > Duration.Zero)) // otherwise it should be SPIN

    private final def copy(
      maxRetries: Option[Int] = this.maxRetries,
      maxSpin: Int = this.maxSpin,
      randomizeSpin: Boolean = this.randomizeSpin,
      maxCede: Int = this.maxCede,
      randomizeCede: Boolean = this.randomizeCede,
      maxSleep: FiniteDuration = this.maxSleep,
      randomizeSleep: Boolean = this.randomizeSleep,
    ): StrategyFull = StrategyFull(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
      maxCede = maxCede,
      randomizeCede = randomizeCede,
      maxSleep = maxSleep,
      randomizeSleep = randomizeSleep,
    )

    final override def equals(that: Any): Boolean = {
      that match {
        case that: StrategyFull =>
          (this.maxRetries == that.maxRetries) &&
          (this.maxSpin == that.maxSpin) &&
          (this.randomizeSpin == that.randomizeSpin) &&
          (this.maxCede == that.maxCede) &&
          (this.randomizeCede == that.randomizeCede) &&
          (this.maxSleep == that.maxSleep) &&
          (this.randomizeSleep == that.randomizeSleep)
        case _ =>
          false
      }
    }

    final override def hashCode: Int = {
      var h = MurmurHash3.mix(0x25c6e9f6, this.maxRetries.##)
      h = MurmurHash3.mix(h, this.maxSpin.##)
      h = MurmurHash3.mix(h, this.randomizeSpin.##)
      h = MurmurHash3.mix(h, this.maxCede.##)
      h = MurmurHash3.mix(h, this.randomizeCede.##)
      h = MurmurHash3.mix(h, this.maxSleep.##)
      h = MurmurHash3.mixLast(h, this.randomizeSleep.##)
      MurmurHash3.finalizeHash(h, 7)
    }

    final override def toString: String = {
      val mr = maxRetries match {
        case None => "∞"
        case Some(mr) => mr.toString
      }
      val die = "⚄"
      val sRand = if (randomizeSpin) die else ""
      val cedeStr = if (maxCede == 0) {
        "0"
      } else {
        s"..${maxCede}${if (randomizeCede) die else ""}"
      }
      val sleepStr = if (maxSleepNanos == 0L) {
        "0"
      } else { // TODO: nicer formatting of Duration
        s"..${maxSleep}${if (randomizeSleep) die else ""}"
      }
      s"RetryStrategy(maxRetries=${mr}, spin=..${maxSpin}${sRand}, cede=${cedeStr}, sleep=${sleepStr})"
    }

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

    private[choam] final override def canSuspend: Boolean =
      true

    private[core] final override def isDebug: Boolean =
      false
  }

  private final object StrategyFull {

    final def apply(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
      maxCede: Int,
      randomizeCede: Boolean,
      maxSleep: FiniteDuration ,
      randomizeSleep: Boolean,
    ): StrategyFull = new StrategyFull(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
      maxCede = maxCede,
      randomizeCede = randomizeCede,
      maxSleep = maxSleep,
      randomizeSleep = randomizeSleep,
    )
  }

  private final class StrategySpin private (
    override val maxRetries: Option[Int],
    override val maxSpin: Int,
    override val randomizeSpin: Boolean,
  ) extends Spin {

    require(maxRetries.forall{ mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
    require(maxSpin > 0)

    private final def copy(
      maxRetries: Option[Int] = this.maxRetries,
      maxSpin: Int = this.maxSpin,
      randomizeSpin: Boolean = this.randomizeSpin,
    ): StrategySpin = StrategySpin(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
    )

    final override def equals(that: Any): Boolean = {
      that match {
        case that: StrategySpin =>
          (this.maxRetries == that.maxRetries) &&
          (this.maxSpin == that.maxSpin) &&
          (this.randomizeSpin == that.randomizeSpin)
        case _ =>
          false
      }
    }

    final override def hashCode: Int = {
      var h = MurmurHash3.mix(0x5a7e3d8a, this.maxRetries.##)
      h = MurmurHash3.mix(h, this.maxSpin.##)
      h = MurmurHash3.mixLast(h, this.randomizeSpin.##)
      MurmurHash3.finalizeHash(h, 3)
    }

    final override def toString: String = {
      val mr = maxRetries match {
        case None => "∞"
        case Some(mr) => mr.toString
      }
      val sRand = if (randomizeSpin) "⚄" else ""
      s"RetryStrategy.Spin(maxRetries=${mr}, spin=..${maxSpin}${sRand})"
    }

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

  private final object StrategySpin {

    final def apply(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
    ): StrategySpin = new StrategySpin(
      maxRetries = maxRetries,
      maxSpin = maxSpin,
      randomizeSpin = randomizeSpin,
    )
  }

  final val Default: Spin =
    this.spin()

  private[choam] final val DefaultSleep: RetryStrategy =
    this.sleep()

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

  private[choam] final object Internal {

    final object Stepper {

      final def apply[F[_]](implicit F: Async[F]): F[Stepper[F]] = {
        F.ref[Deferred[F, Unit]](null).map { state =>
          new Stepper[F](state, F)
        }
      }
    }

    final case class Stepper[F[_]] private (
      private val state: Ref[F, Deferred[F, Unit]],
      private val F: Async[F],
    ) extends RetryStrategy {

      implicit final def asyncF: Async[F] =
        F

      final def newSuspension: F[F[Unit]] = {
        F.deferred[Unit].flatMap { newDef =>

          def go(poll: F ~> F): F[F[Unit]] = state.access.flatMap {
            case (oldDef, trySet) =>
              if (oldDef eq null) {
                trySet(newDef).flatMap { ok =>
                  if (ok) F.pure(poll(newDef.get))
                  else go(poll) // retry
                }
              } else {
                oldDef.tryGet.flatMap {
                  case None =>
                    F.raiseError(new IllegalStateException)
                  case Some(_) =>
                    trySet(newDef).flatMap { ok =>
                      if (ok) F.pure(poll(newDef.get))
                      else go(poll) // retry
                    }
                }
              }
          }

          F.uncancelable(go)
        }
      }

      final def step: F[Unit] = F.uncancelable { _ =>
        state.get.flatMap {
          case null =>
            F.raiseError(new IllegalStateException)
          case d =>
            d.complete(()).flatMap { ok =>
              if (ok) F.unit
              else F.raiseError(new IllegalStateException)
            }
        }
      }

      private[core] final override def isDebug: Boolean =
        true

      private[choam] final override def canSuspend: Boolean =
        true

      final override def maxCede: Int =
        1

      final override def maxRetries: Option[Int] =
        None

      private[core] final override def maxRetriesInt: Int =
        -1

      final override def maxSleep: FiniteDuration =
        0.seconds

      private[core] final override def maxSleepNanos: Long =
        0

      final override def maxSpin: Int =
        1

      final override def randomizeCede: Boolean =
        false

      final override def randomizeSleep: Boolean =
        false

      final override def randomizeSpin: Boolean =
        false

      final override def withCede(cede: Boolean): RetryStrategy = {
        require(cede)
        this
      }

      final override def withMaxCede(maxCede: Int): RetryStrategy = {
        require(maxCede == 1)
        this
      }

      final override def withMaxRetries(maxRetries: Option[Int]): RetryStrategy = {
        require(maxRetries.isEmpty)
        this
      }

      final override def withMaxSleep(maxSleep: FiniteDuration): RetryStrategy = {
        require(maxSleep.toNanos == 0)
        this
      }

      final override def withMaxSpin(maxSpin: Int): RetryStrategy = {
        require(maxSpin == 1)
        this
      }

      final override def withRandomizeCede(randomizeCede: Boolean): RetryStrategy = {
        require(!randomizeCede)
        this
      }

      final override def withRandomizeSleep(randomizeSleep: Boolean): RetryStrategy = {
        require(!randomizeSleep)
        this
      }

      final override def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy = {
        require(!randomizeSpin)
        this
      }

      final override def withSleep(sleep: Boolean): RetryStrategy = {
        require(!sleep)
        this
      }
    }
  }
}
