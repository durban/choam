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

import scala.collection.immutable.{ Set => ScalaSet }

import cats.kernel.{ Hash, Order }
import cats.effect.IO
import cats.effect.instances.all._

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

import core.Ref

final class MapSpecPar_TtrieHash_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with MapSpecParTtrieHash[IO]

final class MapSpecPar_TtrieOrder_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with MapSpecParTtrieOrder[IO]

final class MapSpecPar_TtrieHash_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with MapSpecParTtrieHash[IO]

final class MapSpecPar_TtrieOrder_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with MapSpecParTtrieOrder[IO]


trait MapSpecParTtrieHash[F[_]] extends MapSpecPar[F] { this: McasImplSpec =>
  def mkEmptyMap[K : Hash : Order, V]: F[Map[K, V]] =
    Ttrie.apply[K, V](Ref.AllocationStrategy.Default).run[F].widen
}

trait MapSpecParTtrieOrder[F[_]] extends MapSpecPar[F] { this: McasImplSpec =>
  def mkEmptyMap[K : Hash : Order, V]: F[Map[K, V]] =
    Ttrie.skipListBased[K, V](Ref.AllocationStrategy.Default).run[F].widen
}

trait MapSpecPar[F[_]] extends BaseSpecAsyncF[F] with ScalaCheckEffectSuite { this: McasImplSpec =>

  def mkEmptyMap[K : Hash : Order, V]: F[Map[K, V]]

  test("Parallel get/put/del") {
    val n = 1024
    val v = 42
    PropF.forAllF { (k1: String, k2: String, ks: ScalaSet[String]) =>
      for {
        m <- this.mkEmptyMap[String, Int]
        _ <- (ks - k1 - k2).toList.traverse_ { k =>
          m.put(k, v).run[F]
        }
        t1 = F.both(
          m.put(k1, v).run[F],
          F.both(
            m.get(k1).run[F],
            m.del(k1).run[F],
          ),
        ).replicateA(n)
        t2 = F.both(
          m.put(k2, v).run[F].parReplicateA(n),
          F.both(
            m.get(k2).run[F].parReplicateA(n),
            m.del(k2).run[F].parReplicateA(n),
          ),
        )
        r12 <- F.both(t1, t2)
        (r1, r2) = r12
        _ <- F.delay {
          r1.foreach {
            case (pr, (gr, _)) =>
              assertEquals(pr.getOrElse(v), v)
              assertEquals(gr.getOrElse(v), v)
          }
        }
        _ <- F.delay {
          (r2._1 ++ r2._2._1).foreach { r =>
            assertEquals(r.getOrElse(v), v)
          }
        }
      } yield ()
    }
  }
}
