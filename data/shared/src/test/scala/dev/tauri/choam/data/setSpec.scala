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
import cats.effect.SyncIO

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class SetSpec_Hash_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with SetSpecHash[SyncIO]

final class SetSpec_Ordered_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with SetSpecOrdered[SyncIO]

trait SetSpecHash[F[_]] extends SetSpec[F] { this: McasImplSpec =>
  final override def mkEmptySet[A : Hash : Order]: F[Set[A]] =
    Set.hashSet[A].run[F]
}

trait SetSpecOrdered[F[_]] extends SetSpec[F] { this: McasImplSpec =>
  final override def mkEmptySet[A : Hash : Order]: F[Set[A]] =
    Set.orderedSet[A].run[F]
}

trait SetSpec[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  def mkEmptySet[A : Hash : Order]: F[Set[A]]

  test("Empty contains") {
    PropF.forAllF { (x: String) =>
      mkEmptySet[String].flatMap { s =>
        assertResultF(s.contains.run[F](x), false)
      }
    }
  }

  test("add / contains") {
    PropF.forAllF { (_xs: ScalaSet[String], x: String) =>
      val xs = _xs.filter(_ =!= x)
      for {
        s <- mkEmptySet[String]
        _ <- assertResultF(s.contains.run[F](x), false)
        _ <- xs.toList.traverse { x2 =>
          assertResultF(s.add.run(x2), true)
        }
        _ <- assertResultF(s.contains.run[F](x), false)
        _ <- xs.toList.traverse { x2 =>
          assertResultF(s.contains.run(x2), true)
        }
        _ <- assertResultF(s.contains.run[F](x), false)
      } yield ()
    }
  }

  test("add / remove") {
    PropF.forAllF { (_xs: ScalaSet[String], x: String) =>
      val xs = _xs.filter(_ =!= x)
      for {
        s <- mkEmptySet[String]
        _ <- xs.toList.traverse { x2 =>
          assertResultF(s.add.run(x2), true)
        }
        _ <- assertResultF(s.contains.run(x), false)
        _ <- assertResultF(s.add.run(x), true)
        _ <- assertResultF(s.contains.run(x), true)
        _ <- assertResultF(s.remove.run(x), true)
        _ <- assertResultF(s.contains.run(x), false)
        _ <- assertResultF(s.remove.run(x), false)
        _ <- assertResultF(s.contains.run(x), false)
        _ <- xs.toList.traverse { x2 =>
          assertResultF(s.contains.run(x2), true)
        }
      } yield ()
    }
  }
}
