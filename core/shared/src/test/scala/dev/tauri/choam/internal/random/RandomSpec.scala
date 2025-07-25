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
package internal
package random

import java.lang.Integer.remainderUnsigned
import java.lang.Character.{ isHighSurrogate, isLowSurrogate }
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

import cats.effect.SyncIO
import cats.effect.kernel.Unique
import cats.effect.std.{ UUIDGen, Random, SecureRandom }

import munit.ScalaCheckEffectSuite
import org.scalacheck.effect.PropF

import core.Rxn

final class RandomSpec_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with RandomSpec[SyncIO]

trait RandomSpec[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  final val N = 128

  test("UUIDGen") {
    val gen = UUIDGen[Rxn]
    (1 to N).toList.traverse(_ => gen.randomUUID.run[F]).flatMap { uuidList =>
      uuidList.sliding(2).toList.traverse {
        case prev :: curr :: Nil =>
          assertNotEqualsF(curr, prev) *> (
            assertEqualsF(curr.version, 4) *> assertEqualsF(curr.variant, 2)
          )
        case x =>
          failF[Unit](s"unexpected: ${x.toString}")
      }
    }
  }

  test("Unique") {
    val gen = Unique[Rxn]
    (1 to N).toList.traverse(_ => gen.unique.run[F]).flatMap { tokenList =>
      F.defer {
        val s = new IdentityHashMap[Unique.Token, Unit]
        tokenList.foreach { tok => s.put(tok, ()) }
        assertEqualsF(s.size, N)
      }
    }
  }

  test("secureRandom must implement cats.effect.std.SecureRandom") {
    Rxn.secureRandom : SecureRandom[Rxn]
  }

  // TODO: more tests for Rxn.*Random

  checkRandom("Rxn.fastRandom", _ => F.pure(Rxn.fastRandom))
  checkRandom("Rxn.secureRandom", _ => F.pure(Rxn.secureRandom))
  checkRandom(
    "Rxn.deterministicRandom",
    seed => Rxn.deterministicRandom(seed).run[F].widen[Random[Rxn]],
  )
  checkRandom(
    "MinimalRandom1",
    seed => random.minimalRandom1(seed).run[F],
  )
  checkRandom(
    "MinimalRandom2",
    seed => random.minimalRandom2(seed).run[F],
  )

  test("Rxn.deterministicRandom must be deterministic (1)") {
    PropF.forAllF { (seed: Long) =>
      for {
        dr1 <- Rxn.deterministicRandom(seed).run[F]
        dr2 <- Rxn.deterministicRandom(seed).run[F]
        n1 <- dr1.nextLong.run[F]
        _ <- assertResultF(dr2.nextLong.run[F], n1)
        n2 <- dr1.nextLong.run[F]
        _ <- assertResultF(dr2.nextLong.run[F], n2)
        n3 <- dr1.nextLongBounded(42L).run[F]
        _ <- assertResultF(dr2.nextLongBounded(42L).run[F], n3)
        d1 <- dr1.nextDouble.run[F]
        _ <- assertResultF(dr2.nextDouble.run[F], d1)
        b1 <- dr1.nextBoolean.run[F]
        _ <- assertResultF(dr2.nextBoolean.run[F], b1)
        // make the seeds shift:
        _ <- dr1.nextLong.run[F]
        nx <- dr1.nextLong.run[F]
        ny <- dr2.nextLong.run[F]
        _ <- assertNotEqualsF(ny, nx)
      } yield ()
    }
  }

  test("Rxn.deterministicRandom must be deterministic (2)") {
    checkSame(
      seed => Rxn.deterministicRandom(seed),
      seed => Rxn.deterministicRandom(seed),
    )
  }

  test("Rxn.deterministicRandom must be able to roll back the seed") {
    PropF.forAllF { (seed: Long) =>
      for {
        dr1 <- Rxn.deterministicRandom(seed).run[F]
        dr2 <- Rxn.deterministicRandom(seed).run[F]
        ref <- F.delay(new AtomicLong)
        rxn = dr1.nextLong.flatMap { n =>
          ref.set(n)
          Rxn.unsafe.retry[Int]
        }.?
        _ <- assertResultF(rxn.run[F], None)
        n <- dr1.nextLong.run[F]
        _ <- assertResultF(dr2.nextLong.run[F], n)
        _ <- assertResultF(F.delay(ref.get()), n)
      } yield ()
    }
  }

  test("Rxn.deterministicRandom must generate the same values on JS as on JVM") {
    val seed = 0xcb3cd24fdc6b4d2eL
    val expected = List[Any](
      0xa72188f2612329aeL, 0x48d95371bf5ec4f1L,
      0xd30cb828, 0x5db0cf4a,
      0.014245625397914075d, 0.4724122890885252d,
      0.98776937f, 0.3258993f,
      -1.82254464632059d, 0.9106167032393551d,
      0xac10a878c4eebc82L, 0x61521ba8e6e3d855L,
    )
    for {
      dr <- Rxn.deterministicRandom(seed).run[F]
      tasks = List[F[Any]](
        dr.nextLong.run[F].map[Any](x => x),
        dr.nextLong.run[F].map[Any](x => x),
        dr.nextInt.run[F].map[Any](x => x),
        dr.nextInt.run[F].map[Any](x => x),
        dr.nextDouble.run.map[Any](x => x),
        dr.nextDouble.run[F].map[Any](x => x),
        dr.nextFloat.run[F].map[Any](x => x),
        dr.nextFloat.run[F].map[Any](x => x),
        dr.nextGaussian.run.map[Any](x => x),
        dr.nextGaussian.run[F].map[Any](x => x),
        dr.split.run[F].flatMap(_.nextLong.run[F]).map[Any](x => x),
        dr.nextLong.run[F].map[Any](x => x),
      )
      results <- tasks.sequence
      _ <- assertEqualsF(
        results,
        expected
      )
    } yield ()
  }

  test("Rxn.deterministicRandom must be splittable") {
    PropF.forAllF { (seed: Long) =>
      for {
        dr <- Rxn.deterministicRandom(seed).run[F]
        dr1 <- dr.split.run[F]
        n <- dr.nextLong.run[F]
        dr2 <- dr.split.run[F]
        n1 <- dr1.nextLong.run[F]
        n2 <- dr2.nextLong.run[F]
        _ <- assertNotEqualsF(n, n1)
        _ <- assertNotEqualsF(n, n2)
        _ <- assertNotEqualsF(n1, n2)
      } yield ()
    }
  }

  test("Rxn.deterministicRandom must generate the same values as MinimalRandom") {
    checkSame(
      seed => Rxn.deterministicRandom(seed),
      seed => random.minimalRandom1(seed),
    )
    checkSame(
      seed => Rxn.deterministicRandom(seed),
      seed => random.minimalRandom2(seed),
    )
  }

  private def checkRandom(
    name: String,
    mk: Long => F[Random[Rxn]],
  ): Unit = {
    test(s"${name} nextDouble") {
      checkNextDouble(mk)
    }
    test(s"${name} betweenDouble") {
      checkBetweenDouble(mk)
    }
    test(s"${name} betweenFloat") {
      checkBetweenFloat(mk)
    }
    test(s"${name} nextLong") {
      checkNextLong(mk)
    }
    test(s"${name} nextLongBounded") {
      checkNextLongBounded(mk)
    }
    test(s"${name} shuffleList/Vector") {
      checkShuffle(mk)
    }
    test(s"${name} nextGaussian") {
      checkGaussian(mk)
    }
    test(s"${name} nextAlphaNumeric") {
      checkNextAlphaNumeric(mk)
    }
    test(s"${name} nextPrintableChar") {
      checkPrintableChar(mk)
    }
    test(s"${name} nextString") {
      checkNextString(mk)
    }
  }

  private def assertSameResult[A](fa1: F[A], fa2: F[A], clue: String): F[Unit] = {
    fa1.flatMap { r1 =>
      fa2.flatMap { r2 =>
        (r1, r2) match {
          case (s1: String, s2: String) =>
            assertEqualsF(s1.length(), s2.length(), "String length mismatch") *> (
              assertEqualsF(s1, s2, clue = clue)
            )
          case _ =>
            assertEqualsF(r1, r2, clue = clue)
        }
      }
    }
  }

  private def assertSameRng[A](rng1: Random[Rxn], rng2: Random[Rxn], f: Random[Rxn] => Rxn[A], clue: String): F[Unit] = {
    val fa1 = f(rng1).run[F]
    val fa2 = f(rng2).run[F]
    assertSameResult(fa1, fa2, clue = clue)
  }

  /** Checks that `rnd1` and `rnd2` generates the same numbers */
  private def checkSame(mkRnd1: Long => Rxn[Random[Rxn]], mkRnd2: Long => Rxn[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long, lst: List[Int], bound: Int) =>
      (mkRnd1(seed) * mkRnd2(seed)).run[F].flatMap {
        case (rnd1, rnd2) =>
          def checkBoth[A](f: Random[Rxn] => Rxn[A], clue: String = "different results"): F[Unit] =
            assertSameRng(rnd1, rnd2, f, clue = clue)
          for {
            _ <- checkBoth(_.nextLong, "nextLong")
            _ <- checkBoth(_.nextInt, "nextInt")
            _ <- checkBoth(_.nextBytes(remainderUnsigned(bound, 64)).map(_.toVector))
            _ <- checkBoth(_.nextLongBounded(remainderUnsigned(bound, 1024) + 1L))
            _ <- checkBoth(_.betweenLong(-4L, 45L))
            _ <- checkBoth(_.betweenLong(Long.MinValue, Long.MaxValue - 42L))
            _ <- checkBoth(_.nextIntBounded(remainderUnsigned(bound, 1024) + 1))
            _ <- checkBoth(_.betweenInt(-4, 45))
            _ <- checkBoth(_.betweenInt(Int.MinValue, Int.MaxValue - 42))
            _ <- checkBoth(_.nextDouble)
            _ <- checkBoth(_.betweenDouble(-123.456, 6789.4563))
            _ <- checkBoth(_.nextGaussian)
            _ <- checkBoth(_.nextFloat)
            _ <- checkBoth(_.betweenFloat(-123.456f, 6789.4563f))
            _ <- checkBoth(_.nextBoolean)
            _ <- checkBoth(_.nextBoolean)
            _ <- checkBoth(_.nextAlphaNumeric)
            _ <- checkBoth(_.nextPrintableChar)
            _ <- checkBoth(_.nextString(0))
            _ <- checkBoth(_.nextString(remainderUnsigned(bound, 64)))
            _ <- checkBoth(_.shuffleList(lst))
            _ <- checkBoth(_.shuffleVector(lst.toVector))
          } yield ()
      }
    }
  }

  private def checkNextDouble(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        (rnd.nextDouble.run[F], rnd.nextDouble.run[F]).tupled.flatMap { dd =>
          assertNotEqualsF(dd._1, dd._2) *> (
            assertF((clue(dd._1) >= 0.0d) && (dd._1 < 1.0d)) *> (
              assertF((clue(dd._2) >= 0.0d) && (dd._2 < 1.0d))
            )
          )
        }
      }
    }
  }

  private def checkBetweenDouble(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    def checkOne(rnd: Random[Rxn], minIncl: Double, maxExcl: Double): F[Double] = {
      rnd.betweenDouble(minIncl, maxExcl).run[F].flatMap { d =>
        assertF((clue(d) >= clue(minIncl)) && (d < (clue(maxExcl)))).as(d)
      }
    }
    PropF.forAllNoShrinkF { (seed: Long, d1: Double, d2: Double) =>
      for {
        rnd <- mkRnd(seed)
        // invalid arguments:
        _ <- F.delay(rnd.betweenDouble(d1, d1)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- F.delay(rnd.betweenDouble(d1, Double.NaN)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- F.delay(rnd.betweenDouble(Double.NaN, d1)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- F.delay(rnd.betweenDouble(Double.NaN, Double.NaN)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        // generated arguments:
        minmax = if (d1 < d2) {
          (d1, d2)
        } else if (d2 < d1) {
          (d2, d1)
        } else if ((d1 + d2) > d1) {
          (d1, d1 + d2)
        } else { // really unlikely
          (42.0, 99.0)
        }
        (minIncl, maxExcl) = minmax
        _ <- checkOne(rnd, minIncl, maxExcl)
        // hard problem #1 (rounding):
        origin = -1.0000000000000002 // -0x1.0000000000001p0
        bound = -1.0 // -0x1.0p0
        _ <- checkOne(rnd, origin, bound)
        // hard problem #2 (overflow):
        d <- checkOne(rnd, Double.MinValue, Double.MaxValue)
        // this specific value means there was an unhandled overflow:
        _ <- assertNotEqualsF(d, 1.7976931348623155E308)
        // hard problem #3 (underflow in `/ 2.0`):
        _ <- checkOne(rnd, java.lang.Double.MIN_VALUE, java.lang.Math.nextUp(java.lang.Double.MIN_VALUE))
      } yield ()
    }
  }

  private def checkBetweenFloat(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    def checkOne(rnd: Random[Rxn], minIncl: Float, maxExcl: Float): F[Float] = {
      rnd.betweenFloat(minIncl, maxExcl).run[F].flatMap { d =>
        assertF((clue(d) >= clue(minIncl)) && (d < (clue(maxExcl)))).as(d)
      }
    }
    PropF.forAllNoShrinkF { (seed: Long, f1: Float, f2: Float) =>
      for {
        rnd <- mkRnd(seed)
        // invalid arguments:
        _ <- F.delay(rnd.betweenFloat(f1, f1)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- F.delay(rnd.betweenFloat(f1, Float.NaN)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- F.delay(rnd.betweenFloat(Float.NaN, f1)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- F.delay(rnd.betweenFloat(Float.NaN, Float.NaN)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        // generated arguments:
        minmax = if (f1 < f2) {
          (f1, f2)
        } else if (f2 < f1) {
          (f2, f1)
        } else if ((f1 + f2) > f1) {
          (f1, f1 + f2)
        } else { // really unlikely
          (42.0f, 99.0f)
        }
        (minIncl, maxExcl) = minmax
        _ <- checkOne(rnd, minIncl, maxExcl)
        // hard problem #1 (rounding):
        origin = -1.000001f // -0x1.00001p0
        bound = -1.0f // -0x1.0p0
        _ <- checkOne(rnd, origin, bound)
        // hard problem #2 (overflow):
        f <- checkOne(rnd, Float.MinValue, Float.MaxValue)
        // this specific value means there was an unhandled overflow:
        _ <- assertNotEqualsF(f, 3.4028233E38f)
        // hard problem #3 (underflow in `/ 2.0f`):
        _ <- checkOne(rnd, java.lang.Float.MIN_VALUE, java.lang.Math.nextUp(java.lang.Float.MIN_VALUE))
      } yield ()
    }
  }

  private def checkNextLong(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        (rnd.nextLong.run[F], rnd.nextLong.run[F]).tupled.flatMap { nn =>
          assertNotEqualsF(nn._1, nn._2)
        }
      }
    }
  }

  private def checkNextLongBounded(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long, n: Long) =>
      val bound = if (n > 0L) {
        n
      } else if (n < 0L) {
        val b = Math.abs(n)
        if (b < 0L) Long.MaxValue // was Long.MinValue
        else b
      } else { // was 0
        0xffffL
      }
      mkRnd(seed).flatMap { rnd =>
        rnd.nextLongBounded(bound).run[F].flatMap { n =>
          assertF(clue(n) < bound) *> assertF(clue(n) >= 0L)
        }
      }
    }
  }

  private def checkShuffle(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long, lst: List[Int]) =>
      mkRnd(seed).flatMap { rnd =>
        (rnd.shuffleList(lst).run[F], rnd.shuffleVector(lst.toVector).run[F]).mapN(Tuple2(_, _)).flatMap { lv =>
          val (sl, sv) = lv
          for {
            _ <- assertEqualsF(sl.sorted, lst.sorted)
            _ <- assertEqualsF(sv.sorted, lst.sorted.toVector)
          } yield ()
        }
      }
    }
  }

  private def checkGaussian(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        (rnd.nextGaussian.run[F], rnd.nextGaussian.run[F]).tupled.flatMap {
          case (x, y) =>
            for {
              _ <- assertNotEqualsF(clue(x), clue(y))
              _ <- assertF(x >= -1000.0)
              _ <- assertF(x <= +1000.0)
              _ <- assertF(y >= -1000.0)
              _ <- assertF(y <= +1000.0)
            } yield ()
        }
      }
    }
  }

  private def checkPrintableChar(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        rnd.nextPrintableChar.run[F].flatMap { chr =>
          assertF((chr >= '!') && (chr <= '~'))
        }
      }
    }
  }

  private def checkNextAlphaNumeric(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        rnd.nextAlphaNumeric.run[F].flatMap { alnum =>
          assertF(
            Character.isBmpCodePoint(clue(alnum).toInt) &&
            !Character.isHighSurrogate(alnum) &&
            !Character.isLowSurrogate(alnum)
          ) *> assertF {
            val chr = alnum.toInt
            (
              (chr >= 'a'.toInt) && (chr <= 'z'.toInt) ||
              (chr >= 'A'.toInt) && (chr <= 'Z'.toInt) ||
              (chr >= '0'.toInt) && (chr <= '9'.toInt)
            )
          }
        } *> (
          rnd.nextAlphaNumeric.run[F].replicateA(32).flatMap { alnums =>
            assertF(clue(alnums.toSet.size) >= 8)
          }
        )
      }
    }
  }

  private def checkNextString(mkRnd: Long => F[Random[Rxn]]): PropF[F] = {
    PropF.forAllF { (length: Int, seed: Long) =>
      if ((length >= 0) && (length <= (1024*1024))) {
        mkRnd(seed).flatMap { rnd =>
          rnd.nextString(length).run[F].flatMap { str =>
            assertEqualsF(str.length, length) *> (
              F.delay(checkSurrogates(str))
            )
          }
        }
      } else {
        F.unit
      }
    }
  }

  private def checkSurrogates(str: String): Unit = {
    var idx = 0
    while (idx < str.length) {
      val c = str.charAt(idx)
      if (isLowSurrogate(c)) {
        assert(isHighSurrogate(str.charAt(idx - 1)))
      } else if (isHighSurrogate(c)) {
        assert(isLowSurrogate(str.charAt(idx + 1)))
      }
      idx += 1
    }
  }
}
