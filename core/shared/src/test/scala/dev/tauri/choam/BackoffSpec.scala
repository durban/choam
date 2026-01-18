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

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.util.Try

import cats.effect.kernel.{ Temporal, Poll }

import munit.Location

final class BackoffSpec extends BaseSpec {

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
    assertEquals(Backoff.constTokens(retries = 1, maxBackoff = 16), 2)
    assertEquals(Backoff.constTokens(retries = 2, maxBackoff = 16), 4)
    assertEquals(Backoff.constTokens(retries = 4, maxBackoff = 16), 16)
    assertEquals(Backoff.constTokens(retries = 5, maxBackoff = 16), 16)
    assertEquals(Backoff.constTokens(retries = 5, maxBackoff = 8), 8)
    assertEquals(Backoff.constTokens(retries = 1024*1024, maxBackoff = 32), 32)
    // illegal arguments:
    assert(Try(Backoff.constTokens(retries = -1, maxBackoff = 16)).isFailure)
    assert(Try(Backoff.constTokens(retries = 0, maxBackoff = -32)).isFailure)
  }

  test("Backoff.sleepConst") {
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
    assert(Try(Backoff.sleepConstNanos(retries = -1, maxSleepNanos = 16.micros.toNanos)).isFailure)
    assert(Try(Backoff.sleepConstNanos(retries = -2, maxSleepNanos = 16.micros.toNanos)).isFailure)
    assert(Try(Backoff.sleepConstNanos(retries = 0, maxSleepNanos = -16.micros.toNanos)).isFailure)
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
    check(retries = 1, maxBackoff = 16, expMaxTokens = 4)
    check(retries = 2, maxBackoff = 16, expMaxTokens = 8)
    check(retries = 3, maxBackoff = 16, expMaxTokens = 16)
    check(retries = 4, maxBackoff = 16, expMaxTokens = 32)
    check(retries = 4, maxBackoff = 8, expMaxTokens = 16)
    check(retries = 1024*1024, maxBackoff = 8, expMaxTokens = 16)
    // illegal arguments:
    assert(Try(Backoff.randomTokens(retries = -1, halfMaxBackoff = 16, random = ThreadLocalRandom.current())).isFailure)
    assert(Try(Backoff.randomTokens(retries = -1024*1024, halfMaxBackoff = 16, random = ThreadLocalRandom.current())).isFailure)
  }

  test("Backoff.spin") {
    Backoff.spin(0)
    Backoff.spin(1)
    Backoff.spin(1024)
    Backoff.spin(-1)
    Backoff.spin(-1024)
    Backoff.spin(Int.MinValue)
  }

  private sealed abstract class Foo[A] extends Product with Serializable
  private case class Cede(n: Int) extends Foo[Unit]
  private case class Pause(n: Int) extends Foo[Unit]
  private case class Sleep(d: FiniteDuration) extends Foo[Unit]
  private object Sleep {
    def apply(n: Int): Sleep =
      Sleep(n * Test.slAtom)
  }

  // dummy Temporal to test `Backoff2`:
  private implicit val testTemporal: Temporal[Foo] = new Temporal[Foo] {
    override def pure[A](x: A) = fail("pure")
    override def raiseError[A](e: Throwable) = fail("raiseError")
    override def handleErrorWith[A](fa: Foo[A])(f: Throwable => Foo[A]) = fail("handleErrorWith")
    override def flatMap[A, B](fa: Foo[A])(f: A => Foo[B]) = fail("flatMap")
    override def tailRecM[A, B](a: A)(f: A => Foo[Either[A, B]]) = fail("tailRecM")
    override def forceR[A, B](fa: Foo[A])(fb: Foo[B]) = fail("forceR")
    override def uncancelable[A](body: Poll[Foo] => Foo[A]) = fail("uncancelable")
    override def canceled: Foo[Unit] = fail("canceled")
    override def onCancel[A](fa: Foo[A], fin: Foo[Unit]) = fail("onCancel")
    override def unique = fail("unique")
    override def start[A](fa: Foo[A]) = fail("start")
    override def never[A] = fail("never")
    override def cede = Cede(1)
    override def ref[A](a: A) = fail("ref")
    override def deferred[A] = fail("deferred")
    override def monotonic = fail("monotonic")
    override def realTime = fail("realTime")
    override def sleep(time: FiniteDuration) = Sleep(time)
    override def replicateA_[A](r: Int, fa: Foo[A]) = fa match {
      case Sleep(d) => Sleep(r * d)
      case Cede(n) => Cede(r * n)
      case Pause(n) => Pause(r * n)
    }
  }

  // dummy subclass to test `Backoff2`:
  private class BoTest(ignoreRandomize: Boolean) extends Backoff2 {

    private[this] def convertToken(token: Long): Foo[Unit] = {
      if (this.isPauseToken(token)) {
        val k = (token & BackoffPlatform.backoffTokenMask).toInt
        Pause(k)
      } else {
        this.tokenToF(token)(using testTemporal)
      }
    }

    def backoffStrT(
      retries: Int,
      strategy: RetryStrategy,
      canSuspend: Boolean,
    ): Foo[Unit] = {
      val str = if (ignoreRandomize) {
        if (strategy.canSuspend) {
          if (strategy.maxSleep > Duration.Zero) {
            RetryStrategy.sleep(
              maxRetries = strategy.maxRetries,
              maxSpin = strategy.maxSpin,
              randomizeSpin = false,
              maxCede = strategy.maxCede,
              randomizeCede = false,
              maxSleep = strategy.maxSleep,
              randomizeSleep = false,
            )
          } else {
            RetryStrategy.cede(
              maxRetries = strategy.maxRetries,
              maxSpin = strategy.maxSpin,
              randomizeSpin = false,
              maxCede = strategy.maxCede,
              randomizeCede = false,
            )
          }
        } else {
          RetryStrategy.spin(
            maxRetries = strategy.maxRetries,
            maxSpin = strategy.maxSpin,
            randomizeSpin = false,
          )
        }
      } else {
        strategy
      }
      val tok = this.backoffStrTok(retries, str, canSuspend)
      convertToken(tok)
    }

    def backoffT(
      retries: Int,
      maxPause: Int = BackoffPlatform.maxPauseDefault,
      randomizePause: Boolean = true,
      maxCede: Int = BackoffPlatform.maxCedeDefault,
      randomizeCede: Boolean = true,
      maxSleep: Int = BackoffPlatform.maxSleepDefault,
      randomizeSleep: Boolean = true,
    ): Foo[Unit] = {
      val tok = if (ignoreRandomize) {
        this.backoffTok(
          retries = retries,
          maxPause = maxPause,
          randomizePause = false,
          maxCede = maxCede,
          randomizeCede = false,
          maxSleep = maxSleep,
          randomizeSleep = false,
        )
      } else {
        this.backoffTok(
          retries = retries,
          maxPause = maxPause,
          randomizePause = randomizePause,
          maxCede = maxCede,
          randomizeCede = randomizeCede,
          maxSleep = maxSleep,
          randomizeSleep = randomizeSleep,
        )
      }
      convertToken(tok)
    }

    def slAtom: FiniteDuration = {
      BackoffPlatform.sleepAtomNanos.nanos
    }
  }

  // for most tests, we don't do randomization, for easier testing:
  private object Test extends BoTest(ignoreRandomize = true)

  private val defaultExpected30 = List(
    Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
    Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
    Cede(1), Cede(2), Cede(4), Cede(8),
    Sleep(1), Sleep(2), Sleep(4), Sleep(8),
    Sleep(8), Sleep(8), Sleep(8), Sleep(8),
    Sleep(8), Sleep(8), Sleep(8), Sleep(8),
    Sleep(8),
  )

  test("Backoff2 with defaults") {
    val actual = (1 to 30).map { retries =>
      Test.backoffT(retries)
    }.toList
    assertEquals(actual, defaultExpected30)
  }

  test("Backoff2 with non-default maxPause") {
    val actual0 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxPause = 2048)
    }.toList
    val expected0 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8),
    )
    assertEquals(actual0, expected0)
    val actual1 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxPause = 4095)
    }.toList
    val expected1 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8)
    )
    assertEquals(actual1, expected1)
    val actual2 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxPause = 4097)
    }.toList
    val expected2 = defaultExpected30
    assertEquals(actual2, expected2)
    val actual3 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxPause = 8192)
    }.toList
    val expected3 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096), Pause(8192),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
    )
    assertEquals(actual3, expected3)
  }

  test("Backoff2 with non-default maxCede") {
    val actual0 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxCede = 4)
    }.toList
    val expected0 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8),
    )
    assertEquals(actual0, expected0)
    val actual1 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxCede = 7)
    }.toList
    val expected1 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8),
    )
    assertEquals(actual1, expected1)
    val actual2 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxCede = 9)
    }.toList
    val expected2 = defaultExpected30
    assertEquals(actual2, expected2)
    val actual3 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxCede = 16)
    }.toList
    val expected3 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8), Cede(16),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
    )
    assertEquals(actual3, expected3)
  }

  test("Backoff2 with non-default maxSleep") {
    val actual0 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxSleep = 4)
    }.toList
    val expected0 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(4),
      Sleep(4), Sleep(4), Sleep(4), Sleep(4),
      Sleep(4), Sleep(4), Sleep(4), Sleep(4),
      Sleep(4),
    )
    assertEquals(actual0, expected0)
    val actual1 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxSleep = 7)
    }.toList
    val expected1 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(7),
      Sleep(7), Sleep(7), Sleep(7), Sleep(7),
      Sleep(7), Sleep(7), Sleep(7), Sleep(7),
      Sleep(7),
    )
    assertEquals(actual1, expected1)
    val actual2 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxSleep = 9)
    }.toList
    val expected2 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(9), Sleep(9), Sleep(9), Sleep(9),
      Sleep(9), Sleep(9), Sleep(9), Sleep(9),
      Sleep(9),
    )
    assertEquals(actual2, expected2)
    val actual3 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxSleep = 16)
    }.toList
    val expected3 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(16), Sleep(16), Sleep(16), Sleep(16),
      Sleep(16), Sleep(16), Sleep(16), Sleep(16),
      Sleep(16),
    )
    assertEquals(actual3, expected3)
    // really big maxSleep:
    val ms = 1024*1024
    val actual4 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxSleep = ms)
    }.toList
    val expected4 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(16), Sleep(32), Sleep(64), Sleep(128),
      Sleep(256), Sleep(512), Sleep(1024), Sleep(2048),
      Sleep(4096),
    )
    assertEquals(actual4, expected4)
  }

  test("Backoff2 with non-default everything") {
    val actual1 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxPause = 1000, maxCede = 10, maxSleep = 20)
    }.toList
    val expected1 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(16), Sleep(20), Sleep(20), Sleep(20),
      Sleep(20), Sleep(20), Sleep(20), Sleep(20),
      Sleep(20), Sleep(20), Sleep(20), Sleep(20),
    )
    assertEquals(actual1, expected1)
    val actual2 = (1 to 30).map { retries =>
      Test.backoffT(retries, maxPause = Int.MaxValue, maxCede = 10, maxSleep = 20)
    }.toList
    val expected2 = (1 to 30).map { retries =>
      Pause(1 << (retries - 1))
    }.toList
    assertEquals(actual2, expected2)
  }

  test("Backoff2 illegal args") {
    assert(Try(Test.backoffTok(Int.MinValue)).isFailure)
    assert(Try(Test.backoffTok(-1024)).isFailure)
    assert(Try(Test.backoffTok(-1023)).isFailure)
    assert(Try(Test.backoffTok(-1)).isFailure)
    assert(Try(Test.backoffTok(0)).isFailure)
    assert(Try(Test.backoffTok(31)).isFailure)
    assert(Try(Test.backoffTok(128)).isFailure)
    assert(Try(Test.backoffTok(Int.MaxValue)).isFailure)
  }

  test("Backoff2.backoffStr") {
    val str1 = RetryStrategy.spin(
      maxRetries = None,
      maxSpin = 1000,
      randomizeSpin = false,
    )
    val actual1 = (1 to 30).map { retries =>
      Test.backoffStrT(retries, str1, canSuspend = true)
    }.toList
    val expected1 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512),
    ) ++ List.fill(20)(Pause(1000))
    assertEquals(actual1, expected1)
    val str2 = str1.withCede(5, false)
    val actual2 = (1 to 30).map { retries =>
      Test.backoffStrT(retries, str2, canSuspend = true)
    }.toList
    val expected2 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512),
      Cede(1), Cede(2), Cede(4),
    ) ++ List.fill(17)(Cede(5))
    assertEquals(actual2, expected2)
    val str3 = str2.withSleep(100.millis, false)
    val actual3 = (1 to 30).map { retries =>
      Test.backoffStrT(retries, str3, canSuspend = true)
    }.toList
    val expected3 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512),
      Cede(1), Cede(2), Cede(4),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
    ) ++ List.fill(13)(Sleep(88.millis)) // sleep time is quantized to multiples of sleepAtom
    assertEquals(actual3, expected3)
    // really big maxSleep:
    val str4 = str3.withSleep(10.minutes)
    val actual4 = (1 to 30).map { retries =>
      Test.backoffStrT(retries, str4, canSuspend = true)
    }.toList
    val expected4 = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512),
      Cede(1), Cede(2), Cede(4),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(16), Sleep(32), Sleep(64), Sleep(128),
      Sleep(256), Sleep(512), Sleep(1024), Sleep(2048),
      Sleep(4096), Sleep(8192), Sleep(16384), Sleep(32768),
      Sleep(65536),
    )
    assertEquals(actual4, expected4)
    // accepts retries > 30:
    val actual4b = (1 to 1000).map { retries =>
      Test.backoffStrT(retries, str4, canSuspend = true)
    }.toList
    val expected4b = expected4 ++ List.fill(1000 - expected4.size)(Sleep(65536))
    assertEquals(actual4b, expected4b)
    // canSuspend = false overrides Strategy:
    val actual5 = (1 to 30).map { retries =>
      Test.backoffStrT(retries, str4, canSuspend = false)
    }.toList
    val expected5 = expected1
    assertEquals(actual5, expected5)
  }

  private def checkResults(
    rs: IndexedSeq[Foo[Unit]],
    extract: PartialFunction[Foo[Unit], Int],
    max: Int,
    expAvg: Int,
    tolerance: Int,
  )(implicit loc: Location): Unit = {
    val nums = rs.collect(extract)
    assertEquals(nums.length, rs.length)
    assert(nums.forall { n => (n >= 0) && (n <= max) })
    val avg = nums.sum.toDouble / nums.length.toDouble
    assert((clue(avg) >= (expAvg - tolerance)) && (avg <= (expAvg + tolerance)))
    assert(nums.toSet.size > 1)
    assert(nums.toSet.size < nums.size)
  }

  test("Backoff2 randomization") {
    val Test = new BoTest(ignoreRandomize = false)
    val str0 = RetryStrategy.spin(
      maxRetries = None,
      maxSpin = 1000,
      randomizeSpin = true,
    )
    val str = str0
      .withCede(5, true)
      .withSleep(10.minutes, true)
    val rs1 = for (_ <- 1 to 1000) yield Test.backoffStrT(9, str, canSuspend = true) // Pause(0..256)
    checkResults(rs1, { case Pause(n) => n }, max = 256, expAvg = 128, tolerance = 20)
    val rs2 = for (_ <- 1 to 1000) yield Test.backoffStrT(10, str, canSuspend = true) // Pause(0..512)
    checkResults(rs2, { case Pause(n) => n }, max = 512, expAvg = 256, tolerance = 40)
    val rs3 = for (_ <- 1 to 1000) yield Test.backoffStrT(13, str, canSuspend = true) // Cede(0..4)
    checkResults(rs3, { case Cede(n) => n }, max = 4, expAvg = 2, tolerance = 1)
    val rs4 = for (_ <- 1 to 1000) yield Test.backoffStrT(20, str, canSuspend = true) // Sleep(0..64)
    checkResults(rs4, { case Sleep(n) => java.lang.Math.toIntExact(n.toNanos / Test.slAtom.toNanos) }, max = 64, expAvg = 32, tolerance = 6)
  }

  test("Backoff2.log2floor") {
    assertEquals(Test.log2floor_testing(1), 0)
    assertEquals(Test.log2floor_testing(2), 1)
    assertEquals(Test.log2floor_testing(3), 1)
    assertEquals(Test.log2floor_testing(4), 2)
    assertEquals(Test.log2floor_testing(1023), 9)
    assertEquals(Test.log2floor_testing(1024), 10)
    assertEquals(Test.log2floor_testing(1025), 10)
    assertEquals(Test.log2floor_testing(2047), 10)
    assertEquals(Test.log2floor_testing(2048), 11)
    assertEquals(Test.log2floor_testing(2049), 11)
    assertEquals(Test.log2floor_testing(Int.MaxValue), 30)
  }
}
