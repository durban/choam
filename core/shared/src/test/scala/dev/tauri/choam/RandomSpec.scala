/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.Integer.remainderUnsigned
import java.lang.Character.{ isHighSurrogate, isLowSurrogate }
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

import cats.effect.SyncIO
import cats.effect.kernel.Unique
import cats.effect.std.{ UUIDGen, Random }

import munit.ScalaCheckEffectSuite
import org.scalacheck.effect.PropF

final class RandomSpec_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with RandomSpec[SyncIO]

trait RandomSpec[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  final val N = 128

  test("UUIDGen") {
    val gen = UUIDGen[Axn]
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
    val gen = Unique[Axn]
    (1 to N).toList.traverse(_ => gen.unique.run[F]).flatMap { tokenList =>
      F.defer {
        val s = new IdentityHashMap[Unique.Token, Unit]
        tokenList.foreach { tok => s.put(tok, ()) }
        assertEqualsF(s.size, N)
      }
    }
  }

  // TODO: more tests for Rxn.*Random

  checkRandom("Rxn.fastRandom", _ => Rxn.fastRandom.run[F])
  checkRandom("Rxn.secureRandom", _ => Rxn.secureRandom.run[F])
  checkRandom(
    "Rxn.deterministicRandom",
    seed => Rxn.deterministicRandom(seed).run[F].widen[Random[Axn]],
  )
  checkRandom(
    "MinimalRandom",
    seed => Rxn.minimalRandom(seed).run[F],
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
        rxn = dr1.nextLong.flatMapF { n =>
          ref.set(n)
          Rxn.unsafe.retry[Any, Int]
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
      seed => Rxn.minimalRandom(seed),
    )
  }

  def checkRandom(
    name: String,
    mk: Long => F[Random[Axn]],
    isBuggy: Boolean = false,
  ): Unit = {
    test(s"${name} nextDouble") {
      checkNextDouble(mk)
    }
    test(s"${name} betweenDouble") {
      checkBetweenDouble(isBuggy)(mk)
    }
    test(s"${name} betweenFloat") {
      checkBetweenFloat(isBuggy)(mk)
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

  def assertSameResult[A](fa1: F[A], fa2: F[A], clue: String = "values are not the same"): F[Unit] = {
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

  def assertSameRng[A](rng1: Random[Axn], rng2: Random[Axn], f: Random[Axn] => Axn[A]): F[Unit] = {
    val fa1 = f(rng1).run[F]
    val fa2 = f(rng2).run[F]
    assertSameResult(fa1, fa2)
  }

  /** Checks that `rnd1` and `rnd2` generates the same numbers */
  def checkSame(mkRnd1: Long => Axn[Random[Axn]], mkRnd2: Long => Axn[Random[Axn]]): PropF[F] = {
    PropF.forAllF { (seed: Long, lst: List[Int], bound: Int) =>
      (mkRnd1(seed) * mkRnd2(seed)).run[F].flatMap {
        case (rnd1, rnd2) =>
          def checkBoth[A](f: Random[Axn] => Axn[A]): F[Unit] =
            assertSameRng(rnd1, rnd2, f)
          for {
            _ <- checkBoth(_.nextLong)
            _ <- checkBoth(_.nextInt)
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

  def checkNextDouble(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
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

  def checkBetweenDouble(isBuggy: Boolean)(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
    PropF.forAllF { (seed: Long, d1: Double, d2: Double) =>
      for {
        rnd <- mkRnd(seed)
        _ <- F.delay(rnd.betweenDouble(d1, d1)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- if ((d1 + d2) > d1) {
          rnd.betweenDouble(d1, d1 + d2).run[F].flatMap { d =>
            assertF((clue(d) >= clue(d1)) && (d < (clue(d1 + d2))))
          }
        } else F.unit
        _ <- if (!isBuggy) {
          val origin: Double = -1.0000000000000002 // -0x1.0000000000001p0
          val bound: Double = -1.0 // -0x1.0p0
          rnd.betweenDouble(origin, bound).run[F].flatMap { d =>
            assertF((clue(d) >= clue(origin)) && (d < (clue(bound))))
          }
        } else F.unit
      } yield ()
    }
  }

  def checkBetweenFloat(isBuggy: Boolean)(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
    PropF.forAllF { (seed: Long, f1: Float, f2: Float) =>
      for {
        rnd <- mkRnd(seed)
        _ <- F.delay(rnd.betweenFloat(f1, f1)).flatMap(_.run[F]).attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- if ((f1 + f2) > f1) {
          rnd.betweenFloat(f1, f1 + f2).run[F].flatMap { f =>
            assertF((clue(f) >= clue(f1)) && (f < (clue(f1 + f2))))
          }
        } else F.unit
        _ <- if (!isBuggy) {
          val origin: Float = -1.000001f // -0x1.00001p0
          val bound: Float = -1.0f // -0x1.0p0
          rnd.betweenFloat(origin, bound).run[F].flatMap { f =>
            assertF((clue(f) >= clue(origin)) && (f < (clue(bound))))
          }
        } else F.unit
      } yield ()
    }
  }

  def checkNextLong(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        (rnd.nextLong.run[F], rnd.nextLong.run[F]).tupled.flatMap { nn =>
          assertNotEqualsF(nn._1, nn._2)
        }
      }
    }
  }

  def checkNextLongBounded(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
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

  def checkShuffle(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
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

  def checkGaussian(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
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

  def checkPrintableChar(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
    PropF.forAllF { (seed: Long) =>
      mkRnd(seed).flatMap { rnd =>
        rnd.nextPrintableChar.run[F].flatMap { chr =>
          assertF((chr >= '!') && (chr <= '~'))
        }
      }
    }
  }

  def checkNextAlphaNumeric(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
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

  def checkNextString(mkRnd: Long => F[Random[Axn]]): PropF[F] = {
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
