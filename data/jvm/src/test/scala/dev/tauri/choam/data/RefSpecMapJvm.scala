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
package data

import java.util.concurrent.ThreadLocalRandom

import cats.kernel.{ Hash, Order }
import cats.Applicative
import cats.effect.IO

import core.Ref

final class RefSpec_Map_TtrieHash_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Map_TtrieHash[IO]

final class RefSpec_Map_TtrieOrder_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Map_TtrieOrder[IO]

final class RefSpec_Map_TtrieHash_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpec_Map_TtrieHash[IO]

final class RefSpec_Map_TtrieOrder_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpec_Map_TtrieOrder[IO]

final class RefSpec_Map_TtrieHash_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with RefSpec_Map_TtrieHash[IO]

final class RefSpec_Map_TtrieOrder_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with RefSpec_Map_TtrieOrder[IO]

final class RefSpec_Map_SimpleHash_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpec_Map_SimpleHash[IO]

final class RefSpec_Map_SimpleHash_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with RefSpec_Map_SimpleHash[IO]

final class RefSpec_Map_SimpleOrdered_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpec_Map_SimpleOrdered[IO]

final class RefSpec_Map_SimpleOrdered_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with RefSpec_Map_SimpleOrdered[IO]

trait RefSpec_Map_TtrieHash[F[_]] extends RefSpec_Map_Ttrie[F] { this: McasImplSpec =>
  final override def newMap[K : Hash : Order, V]: F[MapType[K, V]] =
    Ttrie[K, V](Ref.AllocationStrategy.Default).run[F]
}

trait RefSpec_Map_TtrieOrder[F[_]] extends RefSpec_Map_Ttrie[F] { this: McasImplSpec =>
  final override def newMap[K : Hash : Order, V]: F[MapType[K, V]] =
    Ttrie.skipListBased[K, V](Ref.AllocationStrategy.Default).run[F]
}

trait RefSpec_Map_Ttrie[F[_]] extends RefSpecMap[F] { this: McasImplSpec =>

  private[data] final override type MapType[K, V] = Ttrie[K, V]

  test("Ttrie insert/remove should not leak memory") {
    val constValue = "foo"
    val S = 4096
    val N = 8
    def task(m: MapType[String, String], size: Int): F[Unit] = {
      randomStrings(size).flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(NCPU) { key =>
          m.put.run[F](key -> constValue) *> assertResultF(m.get.run[F](key), Some(constValue))
        } >> (
          keys.parTraverseN(NCPU) { key =>
            assertResultF(m.del.run[F](key), true)
          }
        ) >> (
          assertResultF(m.unsafeTrieMapSize.run[F], 0)
        )
      }
    }

    runMemoryReclamationTest(S, N, task, expectedSizeAtEnd = 0)
  }

  test("Ttrie failed lookups should not leak memory") {
    val S = 4096
    val N = 8
    def task(m: MapType[String, String], size: Int): F[Unit] = {
      randomStrings(size).flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(NCPU) { key =>
          assertResultF(m.get.run[F](key), None)
        } >> (
          assertResultF(m.unsafeTrieMapSize.run[F], 0)
        )
      }
    }

    runMemoryReclamationTest(S, N, task, expectedSizeAtEnd = 0)
  }

  test("Ttrie removing not included keys should not leak memory") {
    val S = 4096
    val N = 8
    def task(m: MapType[String, String], size: Int): F[Unit] = {
      randomStrings(size).flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(NCPU) { key =>
          assertResultF(m.del.run[F](key), false)
        } >> (
          assertResultF(m.unsafeTrieMapSize.run[F], 0)
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

  private[this] final def runMemoryReclamationTest[K : Hash : Order, V](
    S: Int,
    N: Int,
    task: (MapType[K, V], Int) => F[Unit],
    expectedSizeAtEnd: Int,
  ): F[Unit] = {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      m <- newMap[K, V]
      _ <- Applicative[F].replicateA(
        N,
        task(m, S) >> assertResultF(m.unsafeTrieMapSize.run[F], expectedSizeAtEnd),
      )
    } yield ()
  }
}
