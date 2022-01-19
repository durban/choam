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

import java.util.SplittableRandom
import java.security.SecureRandom

import cats.effect.SyncIO

import org.scalacheck.effect.PropF

final class RandomSpecJvm_EMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecEMCAS
  with RandomSpecJvm[SyncIO]

final class RandomSpecJvm_ThreadConfinedMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMCAS
  with RandomSpecJvm[SyncIO]

trait RandomSpecJvm[F[_]] extends RandomSpec[F] { this: KCASImplSpec =>

  test("SecureRandom (JVM)") {
    val bt = System.nanoTime()
    val s = new SecureRandom()
    s.nextBytes(new Array[Byte](20)) // force seed
    val at = System.nanoTime()
    println(s"Default SecureRandom: ${s.toString} (in ${at - bt}ns)")
  }

  test("Rxn.deterministicRandom should use the same algo as SplittableRandom") {
    PropF.forAllF { (seed: Long) =>
      for {
        // the basic algorithm is the same as SplittableRandom:
        sr <- F.delay(new SplittableRandom(seed))
        dr <- Rxn.deterministicRandom(seed).run[F]
        n1 <- F.delay(sr.nextLong())
        _ <- assertResultF(dr.nextLong.run[F], n1)
        n2 <- F.delay(sr.nextLong())
        _ <- assertResultF(dr.nextLong.run[F], n2)
        n3 <- F.delay(sr.nextLong(42L))
        _ <- assertResultF(dr.nextLongBounded(42L).run[F], n3)
        d1 <- F.delay(sr.nextDouble())
        _ <- assertResultF(dr.nextDouble.run[F], d1)
        b1 <- F.delay(sr.nextBoolean())
        _ <- assertResultF(dr.nextBoolean.run[F], b1)
        // make the seeds shift:
        _ <- F.delay(sr.nextLong())
        nx <- F.delay(sr.nextLong())
        ny <- dr.nextLong.run[F]
        _ <- assertNotEqualsF(ny, nx)
      } yield ()
    }
  }
}
