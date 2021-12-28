/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.SyncIO
import cats.effect.kernel.Unique
import cats.effect.std.UUIDGen

import munit.ScalaCheckEffectSuite
import org.scalacheck.effect.PropF
import cats.effect.std.Random

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

  test("Rxn.fastRandom betweenDouble") {
    Rxn.fastRandom.run[F].map(checkBetweenDouble)
  }

  test("Rxn.fastRandom nextLong") {
    Rxn.fastRandom.run[F].map(checkNextLong)
  }

  test("Rxn.fastRandom nextAlphaNumeric") {
    Rxn.fastRandom.run[F].map(checkNextAlphaNumeric)
  }

  test("Rxn.fastRandomCached betweenDouble") {
    Rxn.fastRandomCached.run[F].map(checkBetweenDouble)
  }

  test("Rxn.fastRandomCached nextLong") {
    Rxn.fastRandomCached.run[F].map(checkNextLong)
  }

  test("Rxn.fastRandomCached nextAlphaNumeric") {
    Rxn.fastRandomCached.run[F].map(checkNextAlphaNumeric)
  }

  test("Rxn.secureRandom betweenDouble") {
    Rxn.secureRandom.run[F].map(checkBetweenDouble)
  }

  test("Rxn.secureRandom nextLong") {
    Rxn.secureRandom.run[F].map(checkNextLong)
  }

  test("Rxn.secureRandom nextAlphaNumeric") {
    Rxn.secureRandom.run[F].map(checkNextAlphaNumeric)
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
      (rnd.nextLong * rnd.nextLong).run[F].flatMap { nn =>
        assertNotEqualsF(nn._1, nn._2)
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
          val scr = Character.UnicodeScript.of(alnum.toInt)
          (scr eq Character.UnicodeScript.LATIN) || (scr eq Character.UnicodeScript.COMMON)
        }
      } *> (
        rnd.nextAlphaNumeric.replicateA(32).run[F].flatMap { alnums =>
          assertF(clue(alnums.toSet.size) >= 16)
        }
      )
    }
  }
}
