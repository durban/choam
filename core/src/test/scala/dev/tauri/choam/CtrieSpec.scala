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

import cats.Eq

import cats.effect.SyncIO
import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class CtrieSpecNaiveKCAS
  extends BaseSpecSyncIO
  with SpecNaiveKCAS
  with CtrieSpec2[SyncIO]

final class CtrieSpecEMCAS
  extends BaseSpecSyncIO
  with SpecEMCAS
  with CtrieSpec2[SyncIO]

trait CtrieSpec2[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: KCASImplSpec =>

  val hs: Int => Int = { x => x }

  def newEmpty(
    hashFunc: Int => Int = hs,
    eqFunc: (Int, Int) => Boolean = _ == _
  ): F[Ctrie[Int, String]] = F.delay {
    new Ctrie[Int, String](hashFunc, Eq.instance(eqFunc))
  }

  test("Ctrie#lookup should not find anything in an empty trie") {
    PropF.forAllF { (i: Int) =>
      newEmpty().flatMap { ct =>
        assertResultF(ct.lookup.apply[F](i), None)
      }
    }
  }

  test("Ctrie#lookup should find a previously inserted single key") {
    PropF.forAllF { (k: Int, i: Int) =>
      for {
        ct <- newEmpty()
        _ <- ct.insert.apply[F](k -> k.toString)
        _ <- assertResultF(ct.lookup.apply[F](k), Some(k.toString))
        _ <- if (i =!= k) {
          assertResultF(ct.lookup.apply[F](i), None)
        } else {
          F.unit
        }
      } yield ()
    }
  }

  test("Ctrie#lookup should find all previously inserted keys") {
    PropF.forAllF { (ks: Set[Int], x: Int) =>
      for {
        ct <- newEmpty()
        shadow <- F.delay { new scala.collection.mutable.HashSet[Int] }
        _ <- ks.toList.traverse { k =>
          for {
            _ <- ct.insert.apply[F](k -> k.toString)
            _ <- F.delay { shadow += k }
            _ <- shadow.toList.traverse { i =>
              assertResultF(ct.lookup.apply[F](i), Some(i.toString))
            }
            cont <- F.delay { shadow.contains(x) }
            _ <- if (!cont) {
              assertResultF(ct.lookup.apply[F](x), None)
            } else {
              F.unit
            }
          } yield ()
        }
      } yield ()
    }
  }

  test("Ctrie#lookup should find an equal key which is not equal according to universal equality") {
    for {
      ct <- newEmpty(_ % 4, (x, y) => (x % 8) == (y % 8))
      _ <- ct.insert(0 -> "0")
      _ <- assertResultF(ct.lookup(0), Some("0"))
      _ <- assertResultF(ct.lookup(8), Some("0"))
      _ <- ct.insert(4 -> "4")
      _ <- assertResultF(ct.lookup(0), Some("0"))
      _ <- assertResultF(ct.lookup(8), Some("0"))
      _ <- assertResultF(ct.lookup(4), Some("4"))
      _ <- assertResultF(ct.lookup(12), Some("4"))
    } yield ()
  }

  test("Ctrie#insert should handle hash collisions correctly") {
    PropF.forAllF { (ks: Set[Int], x: Int) =>
      for {
        ct <- newEmpty(_ % 8)
        _ <- ct.insert(x -> x.toString)
        _ <- assertResultF(ct.lookup(x), Some(x.toString))
        _ <- ct.insert(x + 8 -> (x + 8).toString)
        _ <- assertResultF(ct.lookup(x), Some(x.toString))
        _ <- assertResultF(ct.lookup(x + 8), Some((x + 8).toString))
        _ <- ct.insert(x + 16 -> (x + 16).toString)
        _ <- assertResultF(ct.lookup(x), Some(x.toString))
        _ <- assertResultF(ct.lookup(x + 8), Some((x + 8).toString))
        _ <- assertResultF(ct.lookup(x + 16), Some((x + 16).toString))
        _ <- ct.insert(x + 1 -> (x + 1).toString)
        _ <- assertResultF(ct.lookup(x), Some(x.toString))
        _ <- assertResultF(ct.lookup(x + 8), Some((x + 8).toString))
        _ <- assertResultF(ct.lookup(x + 16), Some((x + 16).toString))
        _ <- assertResultF(ct.lookup(x + 1), Some((x + 1).toString))
        _ <- ct.insert(x + 9 -> (x + 9).toString)
        _ <- assertResultF(ct.lookup(x), Some(x.toString))
        _ <- assertResultF(ct.lookup(x + 8), Some((x + 8).toString))
        _ <- assertResultF(ct.lookup(x + 16), Some((x + 16).toString))
        _ <- assertResultF(ct.lookup(x + 1), Some((x + 1).toString))
        _ <- assertResultF(ct.lookup(x + 9), Some((x + 9).toString))
        _ <- ks.toList.traverse { k =>
          ct.insert(k -> k.toString).flatMap { _ =>
            assertResultF(ct.lookup(k), Some(k.toString))
          }
        }
        _ <- ct.insert(x + 17 -> (x + 17).toString)
        _ <- assertResultF(ct.lookup(x + 17), Some((x + 17).toString))
      } yield ()
    }
  }

  test("Ctrie#debugStr should pretty print the trie structure") {
    val expStr = """INode -> CNode 3
    |  INode -> CNode 1
    |    INode -> CNode 1
    |      INode -> CNode 1
    |        INode -> CNode 1
    |          INode -> CNode 1
    |            INode -> CNode 1
    |              INode -> LNode(8 -> 8, 4 -> 4)
    |  INode -> CNode 1
    |    INode -> CNode 1
    |      INode -> CNode 1
    |        INode -> CNode 1
    |          INode -> CNode 1
    |            INode -> CNode 1
    |              INode -> LNode(9 -> 9, 5 -> 5)""".stripMargin
    for {
      ct <- F.delay { new Ctrie[Int, String](_ % 4, Eq.instance(_ % 8 == _ % 8)) }
      _ <- ct.insert(0 -> "0")
      _ <- ct.insert(1 -> "1")
      _ <- ct.insert(4 -> "4")
      _ <- ct.insert(5 -> "5")
      _ <- ct.insert(8 -> "8") // overwrites 0
      _ <- ct.insert(9 -> "9") // overwrites 1
      _ <- assertResultF(F.delay { ct.debugStr(this.kcasImpl) }, expStr)
    } yield ()
  }
}
