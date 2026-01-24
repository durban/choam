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

import scala.concurrent.duration._
import scala.util.hashing.MurmurHash3

import cats.{ ~>, Hash, Show }
import cats.syntax.all._
import cats.effect.kernel.{ Async, Ref, Deferred }

sealed abstract class RetryStrategy {

  type CanSuspend <: Boolean

  // SPIN:

  def maxRetries: Option[Int]

  def withMaxRetries(maxRetries: Option[Int]): RetryStrategy.CanSuspend[this.CanSuspend]

  private[choam] def maxRetriesInt: Int

  def maxSpin: Int

  def withMaxSpin(maxSpin: Int): RetryStrategy.CanSuspend[this.CanSuspend]

  def randomizeSpin: Boolean

  def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy.CanSuspend[this.CanSuspend]

  // CEDE:

  final def withCede: RetryStrategy.CanSuspend[true] =
    this.withCede(maxCede = BackoffPlatform.maxCedeDefault, randomizeCede = BackoffPlatform.randomizeCedeDefault)

  final def withCede(maxCede: Int): RetryStrategy.CanSuspend[true] =
    this.withCede(maxCede = maxCede, randomizeCede = BackoffPlatform.randomizeCedeDefault)

  def withCede(maxCede: Int, randomizeCede: Boolean): RetryStrategy.CanSuspend[true]

  def maxCede: Int
  def randomizeCede: Boolean

  // SLEEP:

  final def withSleep: RetryStrategy.CanSuspend[true] =
    this.withSleep(maxSleep = BackoffPlatform.maxSleepDefaultDuration, randomizeSleep = BackoffPlatform.randomizeSleepDefault)

  final def withSleep(maxSleep: FiniteDuration): RetryStrategy.CanSuspend[true] =
    this.withSleep(maxSleep = maxSleep, randomizeSleep = BackoffPlatform.randomizeSleepDefault)

  def withSleep(maxSleep: FiniteDuration, randomizeSleep: Boolean): RetryStrategy.CanSuspend[true]

  def maxSleep: FiniteDuration
  private[choam] def maxSleepNanos: Long
  def randomizeSleep: Boolean

  // MISC.:

  private[choam] def canSuspend: CanSuspend

  private[choam] def isDebug: Boolean
}

object RetryStrategy {

  final type CanSuspend[B <: Boolean] = RetryStrategy {
    type CanSuspend = B
  }

  private[choam] final def isSpin(str: RetryStrategy): Boolean =
    str.isInstanceOf[StrategySpin]

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

  private final class StrategyFull private (
    override val maxRetries: Option[Int],
    override val maxSpin: Int,
    override val randomizeSpin: Boolean,
    override val maxCede: Int,
    override val randomizeCede: Boolean,
    override val maxSleep: FiniteDuration,
    override val randomizeSleep: Boolean,
  ) extends RetryStrategy {

    final override type CanSuspend = true

    require(maxRetries.forall { mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
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

    final override def withMaxRetries(maxRetries: Option[Int]): RetryStrategy.CanSuspend[true] = {
      if (maxRetries == this.maxRetries) this
      else this.copy(maxRetries = maxRetries)
    }

    final override def withMaxSpin(maxSpin: Int): RetryStrategy.CanSuspend[true] = {
      if (maxSpin == this.maxSpin) this
      else this.copy(maxSpin = maxSpin)
    }

    final override def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy.CanSuspend[true] = {
      if (randomizeSpin == this.randomizeSpin) this
      else this.copy(randomizeSpin = randomizeSpin)
    }

    final override def withCede(maxCede: Int, randomizeCede: Boolean): RetryStrategy.CanSuspend[true] = {
      require(maxCede > 0)
      this.copy(
        maxCede = maxCede,
        randomizeCede = randomizeCede,
      )
    }

    final override def withSleep(maxSleep: FiniteDuration, randomizeSleep: Boolean): RetryStrategy.CanSuspend[true] = {
      require(maxSleep > Duration.Zero)
      this.copy(
        maxSleep = maxSleep,
        randomizeSleep = randomizeSleep,
      )
    }

    private[choam] override val maxRetriesInt: Int = maxRetries match {
      case Some(n) => n
      case None => -1
    }

    private[choam] override val maxSleepNanos: Long =
      maxSleep.toNanos

    private[choam] final override def canSuspend: true =
      true

    private[choam] final override def isDebug: Boolean =
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
  ) extends RetryStrategy {

    final override type CanSuspend = false

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
      s"RetryStrategy(maxRetries=${mr}, spin=..${maxSpin}${sRand})"
    }

    final override def withMaxRetries(maxRetries: Option[Int]): RetryStrategy.CanSuspend[false] = {
      if (maxRetries == this.maxRetries) this
      else this.copy(maxRetries = maxRetries)
    }

    final override def withMaxSpin(maxSpin: Int): RetryStrategy.CanSuspend[false] = {
      if (maxSpin == this.maxSpin) this
      else this.copy(maxSpin = maxSpin)
    }

    final override def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy.CanSuspend[false] = {
      if (randomizeSpin == this.randomizeSpin) this
      else this.copy(randomizeSpin = randomizeSpin)
    }

    final override def withCede(maxCede: Int, randomizeCede: Boolean): RetryStrategy.CanSuspend[true] = {
      require(maxCede > 0)
      StrategyFull(
        maxRetries = this.maxRetries,
        maxSpin = this.maxSpin,
        randomizeSpin = this.randomizeSpin,
        maxCede = maxCede,
        randomizeCede = randomizeCede,
        maxSleep = this.maxSleep,
        randomizeSleep = this.randomizeSleep,
      )
    }

    final override def withSleep(maxSleep: FiniteDuration, randomizeSleep: Boolean): RetryStrategy.CanSuspend[true] = {
      require(maxSleep > Duration.Zero)
      StrategyFull(
        maxRetries = this.maxRetries,
        maxSpin = this.maxSpin,
        randomizeSpin = this.randomizeSpin,
        maxCede = this.maxCede,
        randomizeCede = this.randomizeCede,
        maxSleep = maxSleep,
        randomizeSleep = randomizeSleep,
      )
    }

    private[choam] override val maxRetriesInt: Int = maxRetries match {
      case Some(n) => n
      case None => -1
    }

    final override def maxCede: Int =
      0

    final override def randomizeCede: Boolean =
      false

    final override def maxSleep: FiniteDuration =
      Duration.Zero

    private[choam] final override def maxSleepNanos: Long =
      0L

    final override def randomizeSleep: Boolean =
      false

    final override def canSuspend =
      false

    final override def isDebug =
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

  final val Default: RetryStrategy.CanSuspend[false] =
    this.spin()

  private[choam] final val DefaultSleep: RetryStrategy.CanSuspend[true] =
    this.sleep()

  final def spin(
    maxRetries: Option[Int] = None,
    maxSpin: Int = BackoffPlatform.maxPauseDefault,
    randomizeSpin: Boolean = BackoffPlatform.randomizePauseDefault,
  ): RetryStrategy.CanSuspend[false] = {
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
  ): RetryStrategy.CanSuspend[true] = {
    require(maxCede > 0)
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
  ): RetryStrategy.CanSuspend[true] = {
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

      final override type CanSuspend = true

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

      private[choam] final override def isDebug: Boolean =
        true

      private[choam] final override def canSuspend: true =
        true

      final override def maxCede: Int =
        1

      final override def maxRetries: Option[Int] =
        None

      private[choam] final override def maxRetriesInt: Int =
        -1

      final override def maxSleep: FiniteDuration =
        0.seconds

      private[choam] final override def maxSleepNanos: Long =
        0

      final override def maxSpin: Int =
        1

      final override def randomizeCede: Boolean =
        false

      final override def randomizeSleep: Boolean =
        false

      final override def randomizeSpin: Boolean =
        false

      final override def withCede(maxCede: Int, randomizeCede: Boolean): RetryStrategy.CanSuspend[true] = {
        require(maxCede == this.maxCede)
        require(randomizeCede == this.randomizeCede)
        this
      }

      final override def withMaxRetries(maxRetries: Option[Int]): RetryStrategy.CanSuspend[true] = {
        require(maxRetries == this.maxRetries)
        this
      }

      final override def withMaxSpin(maxSpin: Int): RetryStrategy.CanSuspend[true] = {
        require(maxSpin ==this.maxSpin)
        this
      }

      final override def withRandomizeSpin(randomizeSpin: Boolean): RetryStrategy.CanSuspend[true] = {
        require(randomizeSpin == this.randomizeSpin)
        this
      }

      final override def withSleep(maxSleep: FiniteDuration, randomizeSleep: Boolean): RetryStrategy.CanSuspend[true] = {
        require(maxSleep == this.maxSleep)
        require(randomizeSleep == this.randomizeSleep)
        this
      }
    }
  }
}
