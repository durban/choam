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
package data

import java.util.concurrent.ThreadLocalRandom

import cats.kernel.Hash
import cats.Applicative
import cats.effect.IO

final class RefSpec_Map_Ttrie_ThreadConfinedMCAS_IO
  extends BaseSpecIO
  with SpecThreadConfinedMCAS
  with RefSpec_Map_Ttrie[IO]

final class RefSpec_Map_Ttrie_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RefSpec_Map_Ttrie[IO]

final class RefSpec_Map_Ttrie_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RefSpec_Map_Ttrie[IO]

final class RefSpec_Map_Simple_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RefSpec_Map_Simple[IO]

final class RefSpec_Map_Simple_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RefSpec_Map_Simple[IO]

trait RefSpec_Map_Ttrie[F[_]] extends RefSpecMap[F] { this: KCASImplSpec =>

  private[data] final override type MapType[K, V] = Ttrie[K, V]

  final override def newMap[K: Hash, V]: F[MapType[K, V]] =
    Ttrie[K, V].run[F]

  test("Ttrie insert/remove should not leak memory") {
    val constValue = "foo"
    val S = 4096
    val N = 8
    def task(m: Map[String, String], size: Int): F[Unit] = {
      randomStrings(size).flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(NCPU) { key =>
          m.put[F](key -> constValue) *> assertResultF(m.get[F](key), Some(constValue))
        } >> (
          keys.parTraverseN(NCPU) { key =>
            assertResultF(m.del[F](key), true)
          }
        ) >> (
          assertResultF(m.unsafeSnapshot.run[F].map(_.size), 0)
        )
      }
    }

    runMemoryReclamationTest(S, N, task, expectedSizeAtEnd = 0)
  }

  test("Ttrie failed lookups should not leak memory".ignore) { // TODO
    val S = 4096
    val N = 8
    def task(m: Map[String, String], size: Int): F[Unit] = {
      randomStrings(size).flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(NCPU) { key =>
          assertResultF(m.get[F](key), None)
        } >> (
          assertResultF(m.unsafeSnapshot.run[F].map(_.size), 0)
        )
      }
    }

    runMemoryReclamationTest(S, N, task, expectedSizeAtEnd = 0)
  }

  test("Ttrie removing not included keys should not leak memory") {
    val S = 4096
    val N = 8
    def task(m: Map[String, String], size: Int): F[Unit] = {
      randomStrings(size).flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(NCPU) { key =>
          assertResultF(m.del[F](key), false)
        } >> (
          assertResultF(m.unsafeSnapshot.run[F].map(_.size), 0)
        )
      }
    }

    runMemoryReclamationTest(S, N, task, expectedSizeAtEnd = 0)
  }

  private[this] def NCPU =
    Runtime.getRuntime().availableProcessors()

  private[this] final def randomStrings(size: Int): F[Vector[String]] = {
    F.delay {
      require(size > 0)
      val rng = new scala.util.Random(ThreadLocalRandom.current())
      val set = scala.collection.mutable.Set.empty[String]
      while (set.size < size) {
        set += rng.nextString(length = 32)
      }
      rng.shuffle(set.toVector)
    }
  }

  private[this] final def runMemoryReclamationTest[K: Hash, V](
    S: Int,
    N: Int,
    task: (Map[K, V], Int) => F[Unit],
    expectedSizeAtEnd: Int,
  ): F[Unit] = {
    for {
      _ <- assumeF(this.kcasImpl.isThreadSafe)
      m <- newMap[K, V]
      _ <- Applicative[F].replicateA(
        N,
        task(m, S) >> assertResultF(m.unsafeTrieMapSize.run[F], expectedSizeAtEnd),
      )
    } yield ()
  }
}
