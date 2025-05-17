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
import cats.data.Chain
import cats.effect.SyncIO

import org.scalacheck.effect.PropF
import munit.ScalaCheckEffectSuite

final class MapSpec_SimpleHash_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with MapSpecSimpleHash[SyncIO]

final class MapSpec_SimpleOrdered_ThreadConfinedMcas_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMcas
  with MapSpecSimpleOrdered[SyncIO]

trait MapSpecSimpleHash[F[_]] extends MapSpecSimple[F] { this: McasImplSpec =>

  def mkEmptyMap[K : Hash : Order, V]: F[Map.Extra[K, V]] =
    Map.simpleHashMap[K, V].run[F]
}

trait MapSpecSimpleOrdered[F[_]] extends MapSpecSimple[F] { this: McasImplSpec =>

  def mkEmptyMap[K : Hash : Order, V]: F[Map.Extra[K, V]] =
    Map.simpleOrderedMap[K, V].run[F]
}

trait MapSpecSimple[F[_]] extends MapSpec[F] { this: McasImplSpec =>

  override type MyMap[K, V] = Map.Extra[K, V]

  test("Map.Extra should perform `clear` correctly") {
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

  test("Map.Extra should perform `values` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.values.run[F], Vector.empty)
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      v1 <- m.values.run[F]
      _ <- assertEqualsF(v1, Vector("bar", "foo"))
      _ <- (Rxn.pure(99 -> "xyz") >>> m.put).run[F]
      _ <- (Rxn.pure(128 -> "abc") >>> m.put).run[F]
      _ <- m.del[F](42)
      v2 <- m.values.run[F]
      _ <- assertEqualsF(v2, Vector("abc", "xyz"))
    } yield ()
  }

  test("Map.Extra should perform `keys` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.keys.run[F], Chain.empty)
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      v1 <- m.keys.run[F]
      _ <- assertEqualsF(v1.iterator.toSet, ScalaSet(42, 99))
      _ <- (Rxn.pure(99 -> "xyz") >>> m.put).run[F]
      _ <- (Rxn.pure(128 -> "abc") >>> m.put).run[F]
      _ <- m.del[F](42)
      v2 <- m.keys.run[F]
      _ <- assertEqualsF(v2.iterator.toSet, ScalaSet(99, 128))
    } yield ()
  }

  test("Map.Extra should perform `valuesUnsorted` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.valuesUnsorted.run[F], Chain.empty)
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      v1 <- m.valuesUnsorted.run[F]
      _ <- assertEqualsF(v1.iterator.toSet, ScalaSet("bar", "foo"))
      _ <- (Rxn.pure(99 -> "xyz") >>> m.put).run[F]
      _ <- (Rxn.pure(128 -> "abc") >>> m.put).run[F]
      _ <- m.del[F](42)
      v2 <- m.valuesUnsorted.run[F]
      _ <- assertEqualsF(v2.iterator.toSet, ScalaSet("abc", "xyz"))
    } yield ()
  }

  test("Map.Extra should perform `items` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.items.run[F], Chain.empty)
      _ <- (Rxn.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (Rxn.pure(99 -> "bar") >>> m.put).run[F]
      v1 <- m.items.run[F]
      _ <- assertEqualsF(v1.iterator.toSet, ScalaSet(42 -> "foo", 99 -> "bar"))
      _ <- (Rxn.pure(99 -> "xyz") >>> m.put).run[F]
      _ <- (Rxn.pure(128 -> "abc") >>> m.put).run[F]
      _ <- m.del[F](42)
      v2 <- m.items.run[F]
      _ <- assertEqualsF(v2.iterator.toSet, ScalaSet(128 -> "abc", 99 -> "xyz"))
    } yield ()
  }
}

trait MapSpec[F[_]]
  extends BaseSpecSyncF[F]
  with ScalaCheckEffectSuite { this: McasImplSpec =>

  type MyMap[K, V] <: Map[K, V]

  def mkEmptyMap[K : Hash : Order, V]: F[MyMap[K, V]]

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

  test("Map should support custom hash/eqv/order") {
    val myHash: Hash[Int] = new Hash[Int] {
      final override def eqv(x: Int, y: Int): Boolean =
        (x % 8) === (y % 8)
      final override def hash(x: Int): Int =
        (x % 4)
    }
    val myOrder: Order[Int] =
      Order.by[Int, Int](_ % 8)
    for {
      m <- mkEmptyMap[Int, String](using myHash, myOrder)
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
    PropF.forAllF { (ks: ScalaSet[Int], x: Int) =>
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
    val myHash = new Hash[Int] {
      final def eqv(x: Int, y: Int) =
        (x % 8) == (y % 8)
      final def hash(x: Int) =
        x % 4
    }
    val myOrder =
      Order.by[Int, Int](_ % 8)
    for {
      m <- mkEmptyMap[Int, String](using myHash, myOrder)
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
    val myHash = new Hash[Int] {
      final def eqv(x: Int, y: Int) =
        x == y
      final def hash(x: Int) =
        x % 8
    }
    PropF.forAllF { (ks: ScalaSet[Int], x: Int) =>
      for {
        m <- mkEmptyMap[Int, String](using myHash, Order[Int])
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

  test("Map creation API") {
    for {
      m1 <- Map.simpleHashMap[Int, String].run[F]
      m2 <- Map.hashMap[Int, String].run[F]
      m3 <- Map.simpleOrderedMap[Int, String].run[F]
      m4 <- Map.orderedMap[Int, String].run[F]
      _ = (
        m1 : Map.Extra[Int, String],
        m2 : Map[Int, String],
        m3 : Map.Extra[Int, String],
        m4 : Map[Int, String],
      )
    } yield ()
  }

  test("Map#toCats (MapRef)") {
    for {
      m <- mkEmptyMap[String, Option[Int]]
      mapRef = m.toCats[F](default = None)
      r1 = mapRef("a")
      r2 = mapRef("b")
      r3 = mapRef("c")
      _ <- r1.set(Some(42))
      _ <- assertResultF(r1.get, Some(42))
      _ <- r2.update(_ => Some(23))
      _ <- assertResultF(m.get[F]("a"), Some(Some(42)))
      _ <- assertResultF(m.get[F]("b"), Some(Some(23)))
      _ <- assertResultF(m.get[F]("c"), None)
      _ <- r3.set(Some(99))
      _ <- assertResultF(m.get[F]("c"), Some(Some(99)))
      _ <- r3.set(None)
      _ <- assertResultF(m.get[F]("c"), None) // NB: it's `None` and not `Some(None)`
    } yield ()
  }

  test("Map should support null keys/values") {
    val nullTolerantStringHash: Hash[String] = new Hash[String] {
      final override def eqv(x: String, y: String): Boolean = {
        if (x eq y) true
        else if (x eq null) false
        else if (y eq null) false
        else Hash[String].eqv(x, y)
      }
      final override def hash(s: String): Int = {
        s.##
      }
    }
    val nullTolerantStringOrder: Order[String] = { (x, y) =>
      if ((x eq null) && (y eq null)) {
        0
      } else if (x eq null) {
        -1
      } else if (y eq null) {
        1
      } else {
        Order[String].compare(x, y)
      }
    }
    PropF.forAllF { (_k0: String, _ks: List[String], _v: String) =>
      val k0 = if (_k0 ne null) _k0 else "foo"
      val ks = _ks.filter(k => (k ne null) && (k != k0))
      val v = if (_v ne null) _v else "bar"
      for {
        m <- mkEmptyMap[String, String](using nullTolerantStringHash, nullTolerantStringOrder)
        _ <- assertResultF(m.get(null), None)
        _ <- ks.traverse_ { k =>
          m.put[F]((k, v))
        }
        _ <- assertResultF(m.get(null), None)
        _ <- m.put[F]((k0, null))
        _ <- assertResultF(m.get(null), None)
        _ <- assertResultF(m.get(k0), Some(null))
        _ <- ks.traverse_ { k =>
          assertResultF(m.get[F](k), Some(v))
        }
        _ <- assertResultF(m.putIfAbsent((k0, v)), Some(null))
        _ <- assertResultF(m.putIfAbsent((null, "x")), None)
        _ <- assertResultF(m.get(null), Some("x"))
        _ <- assertResultF(m.get(k0), Some(null))
        _ <- ks.traverse_ { k =>
          assertResultF(m.get[F](k), Some(v))
        }
        _ <- assertResultF(m.replace((null, "x", null)), true)
        _ <- assertResultF(m.get(null), Some(null))
        _ <- assertResultF(m.get(k0), Some(null))
        _ <- ks.traverse_ { k =>
          assertResultF(m.get[F](k), Some(v))
        }
      } yield ()
    }
  }
}
