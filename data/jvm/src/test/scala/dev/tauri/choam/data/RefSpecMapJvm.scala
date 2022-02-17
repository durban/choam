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
    val ncpu = Runtime.getRuntime().availableProcessors()
    val S = 4096
    val N = 8
    def task(m: Map[String, String], size: Int): F[Unit] = {
      F.delay {
        require(size > 0)
        val rng = new scala.util.Random(ThreadLocalRandom.current())
        val set = scala.collection.mutable.Set.empty[String]
        while (set.size < size) {
          set += rng.nextString(length = 32)
        }
        rng.shuffle(set.toVector)
      }.flatMap { (keys: Vector[String]) =>
        keys.parTraverseN(ncpu) { key =>
          m.put[F](key -> constValue) *> assertResultF(m.get[F](key), Some(constValue))
        } >> (
          keys.parTraverseN(ncpu) { key =>
            assertResultF(m.del[F](key), true)
          }
        ) >> (
          assertResultF(m.unsafeSnapshot.run[F].map(_.size), 0)
        )
      }
    }

    for {
      m <- newMap[String, String]
      _ <- cats.Applicative[F].replicateA(
        N,
        task(m, size = S) // TODO: this fails: >> assertResultF(m.unsafeTrieMapSize.run[F], 0),
      )
    } yield ()
  }
}
