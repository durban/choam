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

import cats.kernel.Hash
import cats.effect.SyncIO

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class MapSpec_Simple_ThreadConfinedMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMCAS
  with MapSpecSimple[SyncIO]

trait MapSpecSimple[F[_]] extends MapSpec[F] { this: McasImplSpec =>

  override type MyMap[K, V] = Map.Extra[K, V]

  def mkEmptyMap[K: Hash, V]: F[Map.Extra[K, V]] =
    Map.simple[K, V].run[F]

  test("Map.Extra should perform clear correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.clear.run[F], ())
      _ <- assertResultF(m.get.apply[F](42), None)
      _ <- assertResultF(m.get.apply[F](99), None)
      _ <- assertResultF(m.clear.run[F], ())
      _ <- assertResultF(m.get.apply[F](42), None)
      _ <- assertResultF(m.get.apply[F](99), None)
    } yield ()
  }

  test("Map.Extra should perform values correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.values.run[F], Vector.empty)
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      v1 <- m.values.run[F]
      _ <- assertEqualsF(v1.size, 2)
      _ <- assertEqualsF(v1.toSet, Set("foo", "bar"))
      _ <- (Rxn.pure(99 -> "xyz") >>> m.put).run[F]
      _ <- (Rxn.pure(128 -> "abc") >>> m.put).run[F]
      _ <- m.del[F](42)
      v2 <- m.values.run[F]
      _ <- assertEqualsF(v2.size, 2)
      _ <- assertEqualsF(v2.toSet, Set("xyz", "abc"))
    } yield ()
  }
}

trait MapSpec[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  type MyMap[K, V] <: Map[K, V]

  def mkEmptyMap[K: Hash, V]: F[MyMap[K, V]]

  test("Map should perform put correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (Rxn.pure(42 -> 21) >>> m.put).run[F]
      _ <- assertResultF(m.get[F](42), Some(21))
      _ <- (Rxn.pure(44 -> 22) >>> m.put).run[F]
      _ <- assertResultF(m.get[F](42), Some(21))
      _ <- assertResultF(m.get[F](44), Some(22))
      _ <- (Rxn.pure(42 -> 0) >>> m.put).run[F]
      _ <- assertResultF(m.get[F](42), Some(0))
      _ <- assertResultF(m.get[F](44), Some(22))
    } yield ()
  }

  test("Map should perform get correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (Rxn.pure(42 -> 21) >>> m.put).run[F]
      _ <- (Rxn.pure(-56 -> 99) >>> m.put).run[F]
      _ <- assertResultF(m.get.apply[F](42), Some(21))
      _ <- assertResultF(m.get.apply[F](-56), Some(99))
      _ <- assertResultF(m.get.apply[F](56), None)
      _ <- assertResultF(m.get.apply[F](99), None)
      _ <- assertResultF(m.get.apply[F](42), Some(21))
      _ <- assertResultF(m.get.apply[F](999999), None)
    } yield ()
  }

  test("Map should perform del correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (Rxn.pure(42 -> 21) >>> m.put).run[F]
      _ <- (Rxn.pure(0 -> 9) >>> m.put).run[F]
      _ <- assertResultF(m.get.apply[F](42), Some(21))
      _ <- assertResultF(m.get.apply[F](0), Some(9))
      _ <- assertResultF((Rxn.pure(56) >>> m.del).run[F], false)
      _ <- assertResultF(m.get.apply[F](42), Some(21))
      _ <- assertResultF(m.get.apply[F](0), Some(9))
      _ <- assertResultF((Rxn.pure(42) >>> m.del).run[F], true)
      _ <- assertResultF(m.get.apply[F](42), None)
      _ <- assertResultF(m.get.apply[F](0), Some(9))
      _ <- assertResultF((Rxn.pure(42) >>> m.del).run[F], false)
      _ <- assertResultF(m.get.apply[F](42), None)
      _ <- assertResultF(m.get.apply[F](0), Some(9))
    } yield ()
  }

  test("Map should perform replace correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.replace.apply[F]((42, "xyz", "bar")), false)
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.replace.apply[F]((42, "foo", "bar")), true)
      _ <- assertResultF(m.get.apply[F](42), Some("bar"))
      _ <- assertResultF(m.replace.apply[F]((99, "foo", "bar")), false)
      _ <- assertResultF(m.get.apply[F](42), Some("bar"))
    } yield ()
  }

  test("Map should perform remove correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.remove.apply[F](42 -> "x"), false)
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.remove.apply[F](42 -> "foo"), true)
      _ <- assertResultF(m.get.apply[F](42), None)
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.remove.apply[F](42 -> "foo"), false)
      _ <- assertResultF(m.get.apply[F](42), None)
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
    } yield ()
  }

  test("Map should perform putIfAbsent correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.putIfAbsent[F]((42, "xyz")), Some("foo"))
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.putIfAbsent[F]((84, "xyz")), None)
      _ <- assertResultF(m.get.apply[F](42), Some("foo"))
      _ <- assertResultF(m.get.apply[F](99), Some("bar"))
      _ <- assertResultF(m.get.apply[F](84), Some("xyz"))
    } yield ()
  }

  test("Map should support custom hash/eqv") {
    val myHash: Hash[Int] = new Hash[Int] {
      final override def eqv(x: Int, y: Int): Boolean =
        (x % 8) === (y % 8)
      final override def hash(x: Int): Int =
        (x % 4)
    }
    for {
      m <- mkEmptyMap[Int, String](myHash)
      _ <- (Rxn.pure(0 -> "0") >>> m.put).run[F]
      _ <- (Rxn.pure(1 -> "1") >>> m.put).run[F]
      _ <- (Rxn.pure(4 -> "4") >>> m.put).run[F]
      _ <- assertResultF(m.get[F](0), Some("0"))
      _ <- assertResultF(m.get[F](1), Some("1"))
      _ <- assertResultF(m.get[F](4), Some("4"))
      _ <- assertResultF(m.get[F](8), Some("0"))
      _ <- (Rxn.pure(8 -> "8") >>> m.put).run[F] // overwrites "0"
      _ <- assertResultF(m.get[F](0), Some("8"))
      _ <- assertResultF(m.get[F](1), Some("1"))
      _ <- assertResultF(m.get[F](4), Some("4"))
      _ <- assertResultF(m.get[F](8), Some("8"))
      _ <- assertResultF(m.del[F](16), true) // deletes "8"
      _ <- assertResultF(m.get[F](0), None)
      _ <- assertResultF(m.get[F](1), Some("1"))
      _ <- assertResultF(m.get[F](4), Some("4"))
      _ <- assertResultF(m.get[F](8), None)
    } yield ()
  }

  test("Map get should not find anything in an empty map") {
    PropF.forAllF { (i: Int) =>
      mkEmptyMap[Int, Int].flatMap { m =>
        assertResultF(m.get.apply[F](i), None)
      }
    }
  }

  test("Map get should find a previously inserted single key") {
    PropF.forAllF { (k: Int, i: Int) =>
      for {
        m <- mkEmptyMap[Int, String]
        _ <- m.put.apply[F](k -> k.toString)
        _ <- assertResultF(m.get.apply[F](k), Some(k.toString))
        _ <- if (i =!= k) {
          assertResultF(m.get.apply[F](i), None)
        } else {
          F.unit
        }
      } yield ()
    }
  }

  test("Map get should find all previously inserted keys") {
    PropF.forAllF { (ks: Set[Int], x: Int) =>
      for {
        m <- mkEmptyMap[Int, String]
        shadow <- F.delay { new scala.collection.mutable.HashSet[Int] }
        _ <- ks.toList.traverse { k =>
          for {
            _ <- m.put.apply[F](k -> k.toString)
            _ <- F.delay { shadow += k }
            _ <- shadow.toList.traverse { i =>
              assertResultF(m.get.apply[F](i), Some(i.toString))
            }
            cont <- F.delay { shadow.contains(x) }
            _ <- if (!cont) {
              assertResultF(m.get.apply[F](x), None)
            } else {
              F.unit
            }
          } yield ()
        }
      } yield ()
    }
  }

  test("Map get should find an equal key which is not equal according to universal equality") {
    for {
      m <- mkEmptyMap[Int, String](new Hash[Int] {
        final def eqv(x: Int, y: Int) =
          (x % 8) == (y % 8)
        final def hash(x: Int) =
          x % 4
      })
      _ <- m.put[F](0 -> "0")
      _ <- assertResultF(m.get(0), Some("0"))
      _ <- assertResultF(m.get(8), Some("0"))
      _ <- m.put(4 -> "4")
      _ <- assertResultF(m.get(0), Some("0"))
      _ <- assertResultF(m.get(8), Some("0"))
      _ <- assertResultF(m.get(4), Some("4"))
      _ <- assertResultF(m.get(12), Some("4"))
    } yield ()
  }

  test("Map del should remove the kv pair") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- m.put[F](0 -> "0")
      _ <- assertResultF(m.get(0), Some("0"))
      _ <- assertResultF(m.del[F](0), true)
      _ <- assertResultF(m.del[F](0), false)
      _ <- m.put(1 -> "1")
      _ <- m.put(2 -> "2")
      _ <- m.put(3 -> "3")
      _ <- m.put(4 -> "4")
      _ <- m.put(5 -> "5")
      _ <- m.put(6 -> "6")
      _ <- m.put(7 -> "7")
      _ <- m.put(8 -> "8")
      _ <- assertResultF(m.get(1), Some("1"))
      _ <- assertResultF(m.del[F](1), true)
      _ <- assertResultF(m.del[F](1), false)
      _ <- assertResultF(m.get(8), Some("8"))
      _ <- assertResultF(m.del[F](8), true)
      _ <- assertResultF(m.del[F](8), false)
    } yield ()
  }

  test("Map insertion should handle hash collisions correctly") {
    PropF.forAllF { (ks: Set[Int], x: Int) =>
      for {
        m <- mkEmptyMap[Int, String](new Hash[Int] {
          final def eqv(x: Int, y: Int) =
            x == y
          final def hash(x: Int) =
            x % 8
        })
        _ <- m.put[F](x -> x.toString)
        _ <- assertResultF(m.get(x), Some(x.toString))
        _ <- m.put(x + 8 -> (x + 8).toString)
        _ <- assertResultF(m.get(x), Some(x.toString))
        _ <- assertResultF(m.get(x + 8), Some((x + 8).toString))
        _ <- m.put(x + 16 -> (x + 16).toString)
        _ <- assertResultF(m.get(x), Some(x.toString))
        _ <- assertResultF(m.get(x + 8), Some((x + 8).toString))
        _ <- assertResultF(m.get(x + 16), Some((x + 16).toString))
        _ <- m.put(x + 1 -> (x + 1).toString)
        _ <- assertResultF(m.get(x), Some(x.toString))
        _ <- assertResultF(m.get(x + 8), Some((x + 8).toString))
        _ <- assertResultF(m.get(x + 16), Some((x + 16).toString))
        _ <- assertResultF(m.get(x + 1), Some((x + 1).toString))
        _ <- m.put(x + 9 -> (x + 9).toString)
        _ <- assertResultF(m.get(x), Some(x.toString))
        _ <- assertResultF(m.get(x + 8), Some((x + 8).toString))
        _ <- assertResultF(m.get(x + 16), Some((x + 16).toString))
        _ <- assertResultF(m.get(x + 1), Some((x + 1).toString))
        _ <- assertResultF(m.get(x + 9), Some((x + 9).toString))
        _ <- ks.toList.traverse { k =>
          m.put(k -> k.toString).flatMap { _ =>
            assertResultF(m.get(k), Some(k.toString))
          }
        }
        _ <- m.put(x + 17 -> (x + 17).toString)
        _ <- assertResultF(m.get(x + 17), Some((x + 17).toString))
      } yield ()
    }
  }

  test("Resurrecting a tombed but uncommitted ref") {
    for {
      m <- mkEmptyMap[Int, String]
      ctr <- Ref(0).run[F]
      _ <- m.put[F](0 -> "x")
      _ <- m.put(0 -> "0")
      _ <- m.put(1 -> "1")
      _ <- m.put(2 -> "2")
      _ <- m.put(3 -> "3")
      _ <- m.put(4 -> "4")
      ref = m.del.provide(0) >>> ctr.update(_ + 1) >>> m.put.provide(0 -> "y")
      _ <- ref.run[F]
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(m.get[F](0), Some("y"))
    } yield ()
  }
}
