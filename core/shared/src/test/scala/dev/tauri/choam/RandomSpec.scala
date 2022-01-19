/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.IdentityHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

import cats.effect.SyncIO
import cats.effect.kernel.Unique
import cats.effect.std.{ UUIDGen, Random }

import munit.ScalaCheckEffectSuite
import org.scalacheck.effect.PropF

final class RandomSpec_ThreadConfinedMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMCAS
  with RandomSpec[SyncIO]

trait RandomSpec[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: KCASImplSpec =>

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

  checkRandom("Rxn.fastRandom", Rxn.fastRandom.run[F])
  checkRandom("Rxn.fastRandomCached", Rxn.fastRandomCached.run[F])
  checkRandom("Rxn.fastRandomCtxSupp", Rxn.fastRandomCtxSupp.run[F])
  checkRandom("Rxn.secureRandom", Rxn.secureRandom.run[F])

  test("Rxn.deterministicRandom nextLong") {
    val mk = F.delay(ThreadLocalRandom.current().nextLong()).flatMap { initSeed =>
      Rxn.deterministicRandom(initSeed).run[F]
    }
    mk.map(checkNextLong)
  }

  test("Rxn.deterministicRandom nextLongBounded") {
    val mk = F.delay(ThreadLocalRandom.current().nextLong()).flatMap { initSeed =>
      Rxn.deterministicRandom(initSeed).run[F]
    }
    mk.map(checkNextLongBounded)
  }

  test("Rxn.deterministicRandom nextGaussian") {
    val mk = F.delay(ThreadLocalRandom.current().nextLong()).flatMap { initSeed =>
      Rxn.deterministicRandom(initSeed).run[F]
    }
    mk.map(checkGaussian)
  }

  test("Rxn.deterministicRandom must be deterministic") {
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
    )
    for {
      dr <- Rxn.deterministicRandom(seed).run[F]
      n1 <- dr.nextLong.run[F]
      n2 <- dr.nextLong.run[F]
      i1 <- dr.nextInt.run[F]
      i2 <- dr.nextInt.run[F]
      d1 <- dr.nextDouble.run[F]
      d2 <- dr.nextDouble.run[F]
      f1 <- dr.nextFloat.run[F]
      f2 <- dr.nextFloat.run[F]
      g1 <- dr.nextGaussian.run[F]
      g2 <- dr.nextGaussian.run[F]
      _ <- assertEqualsF(
        List[Any](
          n1, n2,
          i1, i2,
          d1, d2,
          f1, f2,
          g1, g2,
        ),
        expected
      )
    } yield ()
  }

  def checkRandom(name: String, mk: F[Random[Axn]]): Unit = {
    test(s"${name} betweenDouble") {
      mk.map(checkBetweenDouble)
    }
    test(s"${name} nextLong") {
      mk.map(checkNextLong)
    }
    test(s"${name} nextLongBounded") {
      mk.map(checkNextLongBounded)
    }
    test(s"${name} nextAlphaNumeric") {
      mk.map(checkNextAlphaNumeric)
    }
    test(s"${name} shuffleList/Vector") {
      mk.map(checkShuffle)
    }
    test(s"${name} nextGaussian") {
      mk.map(checkGaussian)
    }
  }

  def checkBetweenDouble(rnd: Random[Axn]): PropF[F] = {
    PropF.forAllF { (d1: Double, d2: Double) =>
      for {
        _ <- rnd.betweenDouble(d1, d1).run[F].attempt.flatMap { x =>
          assertF(x.isLeft)
        }
        _ <- if ((d1 + d2) > d1) {
          rnd.betweenDouble(d1, d1 + d2).run[F].flatMap { d =>
            assertF((d >= d1) && (d < (d1 + d2)))
          }
        } else F.unit
      } yield ()
    }
  }

  def checkNextLong(rnd: Random[Axn]): PropF[F] = {
    PropF.forAllF { (_: Long) =>
      (rnd.nextLong.run[F], rnd.nextLong.run[F]).tupled.flatMap { nn =>
        assertNotEqualsF(nn._1, nn._2)
      }
    }
  }

  def checkNextLongBounded(rnd: Random[Axn]): PropF[F] = {
    PropF.forAllF { (n: Long) =>
      val bound = if (n > 0L) {
        n
      } else if (n < 0L) {
        val b = Math.abs(n)
        if (b < 0L) Long.MaxValue // was Long.MinValue
        else b
      } else { // was 0
        0xffffL
      }
      rnd.nextLongBounded(bound).run[F].flatMap { n =>
        assertF(clue(n) < bound) *> assertF(clue(n) >= 0L)
      }
    }
  }

  def checkNextAlphaNumeric(rnd: Random[Axn]): PropF[F] = {
    PropF.forAllF { (_: Long) =>
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
        rnd.nextAlphaNumeric.replicateA(32).run[F].flatMap { alnums =>
          assertF(clue(alnums.toSet.size) >= 16)
        }
      )
    }
  }

  def checkShuffle(rnd: Random[Axn]): PropF[F] = {
    PropF.forAllF { (lst: List[Int]) =>
      (rnd.shuffleList(lst), rnd.shuffleVector(lst.toVector)).mapN(Tuple2(_, _)).run[F].flatMap { lv =>
        val (sl, sv) = lv
        for {
          _ <- assertEqualsF(sl.sorted, lst.sorted)
          _ <- assertEqualsF(sv.sorted, lst.sorted.toVector)
        } yield ()
      }
    }
  }

  def checkGaussian(rnd: Random[Axn]): PropF[F] = {
    PropF.forAllF { (_: Long) =>
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
