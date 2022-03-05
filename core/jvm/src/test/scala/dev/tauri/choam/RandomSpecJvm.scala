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

import java.util.{ SplittableRandom => JSplittableRandom }
import java.security.SecureRandom

import cats.effect.SyncIO
import cats.effect.std.Random

import org.scalacheck.effect.PropF

import random.SplittableRandom

final class RandomSpecJvm_Emcas_SyncIO
  extends BaseSpecSyncIO
  with SpecEmcas
  with RandomSpecJvm[SyncIO]

final class RandomSpecJvm_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with RandomSpecJvm[SyncIO]

trait RandomSpecJvm[F[_]] extends RandomSpec[F] { this: McasImplSpec =>

  test("SecureRandom (JVM)") {
    val bt = System.nanoTime()
    val s = new SecureRandom()
    s.nextBytes(new Array[Byte](20)) // force seed
    val at = System.nanoTime()
    println(s"Default SecureRandom: ${s.toString} (in ${at - bt}ns)")
  }

  test("Rxn.deterministicRandom should use the same algo as SplittableRandom") {
    PropF.forAllF { (seed: Long) =>
      def checkLong(label: String, sr: JSplittableRandom, dr: Random[Axn]): F[Unit] = for {
        n1 <- F.delay(sr.nextLong())
        _ <- assertResultF(dr.nextLong.run[F], n1, label)
        n2 <- F.delay(sr.nextLong())
        _ <- assertResultF(dr.nextLong.run[F], n2, label)
        n3 <- F.delay(sr.nextLong(42L))
        _ <- assertResultF(dr.nextLongBounded(42L).run[F], n3, label)
        n4 <- F.delay(sr.nextLong(128L))
        _ <- assertResultF(dr.nextLongBounded(128L).run[F], n4, label)
        n5 <- F.delay(sr.nextLong(23L, 1936L))
        _ <- assertResultF(dr.betweenLong(23L, 1936L).run[F], n5, label)
        n6 <- F.delay(sr.nextLong(Long.MinValue + 5L, Long.MaxValue - 32L))
        _ <- assertResultF(dr.betweenLong(Long.MinValue + 5L, Long.MaxValue - 32L).run[F], n6, label)
      } yield ()
      def checkInt(sr: JSplittableRandom, dr: Random[Axn]): F[Unit] = for {
        i1 <- F.delay(sr.nextInt())
        _ <- assertResultF(dr.nextInt.run[F], i1)
        i2 <- F.delay(sr.nextInt(32))
        _ <- assertResultF(dr.nextIntBounded(32).run[F], i2)
        i3 <- F.delay(sr.nextInt(42))
        _ <- assertResultF(dr.nextIntBounded(42).run[F], i3)
        i4 <- F.delay(sr.nextInt(128, 145))
        _ <- assertResultF(dr.betweenInt(128, 145).run[F], i4)
        i5 <- F.delay(sr.nextInt(Int.MinValue, 988595849))
        _ <- assertResultF(dr.betweenInt(Int.MinValue, 988595849).run[F], i5)
      } yield ()
      def checkDouble(sr: JSplittableRandom, dr: Random[Axn]): F[Unit] = for {
        d1 <- F.delay(sr.nextDouble())
        _ <- assertResultF(dr.nextDouble.run[F], d1)
        d2 <- F.delay(sr.nextDouble())
        _ <- assertResultF(dr.nextDouble.run[F], d2)
        d3 <- F.delay(sr.nextDouble(-6534.987, 9853.678))
        _ <- assertResultF(dr.betweenDouble(-6534.987, 9853.678).run[F], d3)
      } yield ()
      def checkBoolean(sr: JSplittableRandom, dr: Random[Axn]): F[Unit] = for {
        b1 <- F.delay(sr.nextBoolean())
        _ <- assertResultF(dr.nextBoolean.run[F], b1)
        b2 <- F.delay(sr.nextBoolean())
        _ <- assertResultF(dr.nextBoolean.run[F], b2)
      } yield ()
      def checkBytes(sr: JSplittableRandom, dr: Random[Axn]): F[Unit] = for {
        a1 <- F.delay(new Array[Byte](16))
        _ <- F.delay(sr.nextBytes(a1))
        a1Actual <- dr.nextBytes(16).run[F]
        _ <- assertEqualsF(a1Actual.toList, a1.toList)
        a2 <- F.delay(new Array[Byte](42))
        _ <- F.delay(sr.nextBytes(a2))
        a2Actual <- dr.nextBytes(42).run[F]
        _ <- assertEqualsF(a2Actual.toList, a2.toList)
        a3 <- F.delay(new Array[Byte](3))
        _ <- F.delay(sr.nextBytes(a3))
        a3Actual <- dr.nextBytes(3).run[F]
        _ <- assertEqualsF(a3Actual.toList, a3.toList)
      } yield ()
      def checkSplit(sr: JSplittableRandom, dr: SplittableRandom[Axn]): F[Unit] = for {
        sr1 <- F.delay(sr.split())
        sr2 <- F.delay(sr.split())
        dr1 <- dr.split.run[F]
        dr2 <- dr.split.run[F]
        _ <- checkLong("split-sr1", sr1, dr1)
        _ <- checkDouble(sr1, dr1)
        _ <- checkLong("split-sr", sr, dr)
        _ <- checkDouble(sr, dr)
        _ <- checkLong("split-sr2", sr2, dr2)
        _ <- checkDouble(sr2, dr2)
      } yield ()
      for {
        // the basic algorithm is the same as SplittableRandom:
        sr <- F.delay(new JSplittableRandom(seed))
        dr <- Rxn.deterministicRandom(seed).run[F]
        _ <- checkLong("sr", sr, dr)
        _ <- checkInt(sr, dr)
        _ <- checkDouble(sr, dr)
        _ <- checkBoolean(sr, dr)
        _ <- checkBytes(sr, dr)
        _ <- checkSplit(sr, dr)
        // last check:
        nLast <- F.delay(sr.nextLong())
        _ <- assertResultF(dr.nextLong.run[F], nLast)
        // negative test, make the seeds shift:
        _ <- F.delay(sr.nextLong())
        nx <- F.delay(sr.nextLong())
        ny <- dr.nextLong.run[F]
        _ <- assertNotEqualsF(ny, nx)
      } yield ()
    }
  }
}
