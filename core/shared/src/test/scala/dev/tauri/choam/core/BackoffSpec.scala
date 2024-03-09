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
import scala.util.Try

import cats.effect.kernel.{ Temporal, Poll }
import cats.effect.kernel.GenTemporal

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

  private sealed abstract class Foo[A]
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
  private object Test extends Backoff2 {
    override def pause[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit] =
      Pause(n).asInstanceOf[F[Unit]]
    override def cede[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit] =
      F.replicateA_(n, F.cede)
    override def sleep[F[_]](n: Int)(implicit F: GenTemporal[F, _]): F[Unit] =
      F.sleep(n * sleepAtom)
    def slAtom: FiniteDuration =
      sleepAtom
  }

  test("Backoff2 with defaults") {
    val actual = (1 to 30).map { retries =>
      Test.backoff[Foo](retries)
    }.toList
    val expected = List(
      Pause(1), Pause(2), Pause(4), Pause(8), Pause(16), Pause(32), Pause(64),
      Pause(128), Pause(256), Pause(512), Pause(1024), Pause(2048), Pause(4096),
      Cede(1), Cede(2), Cede(4), Cede(8),
      Sleep(1), Sleep(2), Sleep(4), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8), Sleep(8), Sleep(8), Sleep(8),
      Sleep(8),
    )
    assertEquals(actual, expected)
  }

  test("Backoff2 illegal args") {
    assert(Try(Test.backoff(Int.MinValue)).isFailure)
    assert(Try(Test.backoff(-1024)).isFailure)
    assert(Try(Test.backoff(-1023)).isFailure)
    assert(Try(Test.backoff(-1)).isFailure)
    assert(Try(Test.backoff(0)).isFailure)
    assert(Try(Test.backoff(31)).isFailure)
    assert(Try(Test.backoff(128)).isFailure)
    assert(Try(Test.backoff(Int.MaxValue)).isFailure)
  }

  test("Backoff2 log2ceil") {
    // examples:
    assertEquals(Test.log2ceil_testing(1), 0)
    assertEquals(Test.log2ceil_testing(2), 1)
    assertEquals(Test.log2ceil_testing(3), 2)
    assertEquals(Test.log2ceil_testing(4), 2)
    assertEquals(Test.log2ceil_testing(1023), 10)
    assertEquals(Test.log2ceil_testing(1024), 10)
    assertEquals(Test.log2ceil_testing(1025), 11)
    assertEquals(Test.log2ceil_testing(2047), 11)
    assertEquals(Test.log2ceil_testing(2048), 11)

    // exhaustive test:
    def log2ceil_correct(x: Int): Int = {
      val fl = Test.log2floor_testing(x)
      // add 1 is `x` is NOT a power of 2:
      if ((x & (x - 1)) == 0) { // power of 2
        fl
      } else {
        fl + 1
      }
    }

    if (!this.isCI) { // too long for CI
      var i = 1 // log2ceil only works for positive ints
      while (i < Integer.MAX_VALUE) {
        assertEquals(Test.log2ceil_testing(i), log2ceil_correct(i))
        i += 1
      }
    }

    assertEquals(Test.log2ceil_testing(Integer.MAX_VALUE), log2ceil_correct(Integer.MAX_VALUE))
  }
}
