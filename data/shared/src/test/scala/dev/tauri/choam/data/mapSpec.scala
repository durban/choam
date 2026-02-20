/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import core.Ref

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
      _ <- (m.put(42, "foo")).run[F]
      _ <- (m.put(99, "bar")).run[F]
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.clear.run[F], ())
      _ <- assertResultF(m.get(42).run[F], None)
      _ <- assertResultF(m.get(99).run[F], None)
      _ <- assertResultF(m.clear.run[F], ())
      _ <- assertResultF(m.get(42).run[F], None)
      _ <- assertResultF(m.get(99).run[F], None)
    } yield ()
  }

  test("Map.Extra should perform `keys` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.keys.run[F], Chain.empty)
      _ <- (m.put(42, "foo")).run[F]
      _ <- (m.put(99, "bar")).run[F]
      v1 <- m.keys.run[F]
      _ <- assertEqualsF(v1.iterator.toSet, ScalaSet(42, 99))
      _ <- {
        val k: Int = v1.get(0).getOrElse(fail("missing"))
        assertF((clue(k) == 42) || (k == 99))
      }
      _ <- (m.put(99, "xyz")).run[F]
      _ <- (m.put(128, "abc")).run[F]
      _ <- m.del(42).run[F]
      v2 <- m.keys.run[F]
      _ <- assertEqualsF(v2.iterator.toSet, ScalaSet(99, 128))
    } yield ()
  }

  test("Map.Extra should perform `values` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.values.run[F], Chain.empty)
      _ <- (m.put(42, "foo")).run[F]
      _ <- (m.put(99, "bar")).run[F]
      v1 <- m.values.run[F]
      _ <- assertEqualsF(v1.iterator.toSet, ScalaSet("bar", "foo"))
      _ <- (m.put(99, "xyz")).run[F]
      _ <- (m.put(128, "abc")).run[F]
      _ <- m.del(42).run[F]
      v2 <- m.values.run[F]
      _ <- assertEqualsF(v2.iterator.toSet, ScalaSet("abc", "xyz"))
    } yield ()
  }

  test("Map.Extra should perform `items` correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- assertResultF(m.items.run[F], Chain.empty)
      _ <- (m.put(42, "foo")).run[F]
      _ <- (m.put(99, "bar")).run[F]
      v1 <- m.items.run[F]
      _ <- assertEqualsF(v1.iterator.toSet, ScalaSet(42 -> "foo", 99 -> "bar"))
      _ <- (m.put(99, "xyz")).run[F]
      _ <- (m.put(128, "abc")).run[F]
      _ <- m.del(42).run[F]
      v2 <- m.items.run[F]
      _ <- assertEqualsF(v2.iterator.toSet, ScalaSet(128 -> "abc", 99 -> "xyz"))
    } yield ()
  }

  test("Map.Extra invariant functor instance") {
    for {
      m <- mkEmptyMap[String, String]
      _ <- m.put("foo", "42").run
      m2 = (m: Map.Extra[String, String]).imap(_.toInt)(_.toString)
      _ <- assertResultF(m2.keys.run, Chain("foo"))
      _ <- assertResultF(m2.items.run, Chain(("foo", 42)))
      r = m2.refLike("foo", 0)
      _ <- r.update(_ + 1).run
      _ <- assertResultF(m.get("foo").run, Some("43"))
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
      _ <- (m.put(42, 21)).run[F]
      _ <- assertResultF(m.get(42).run[F], Some(21))
      _ <- (m.put(44, 22)).run[F]
      _ <- assertResultF(m.get(42).run[F], Some(21))
      _ <- assertResultF(m.get(44).run[F], Some(22))
      _ <- (m.put(42, 0)).run[F]
      _ <- assertResultF(m.get(42).run[F], Some(0))
      _ <- assertResultF(m.get(44).run[F], Some(22))
    } yield ()
  }

  test("Map should perform get correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (m.put(42, 21)).run[F]
      _ <- (m.put(-56, 99)).run[F]
      _ <- assertResultF(m.get(42).run, Some(21))
      _ <- assertResultF(m.get(-56).run, Some(99))
      _ <- assertResultF(m.get(56).run, None)
      _ <- assertResultF(m.get(99).run, None)
      _ <- assertResultF(m.get(42).run, Some(21))
      _ <- assertResultF(m.get(999999).run, None)
    } yield ()
  }

  test("Map should perform del correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (m.put(42, 21)).run[F]
      _ <- (m.put(0, 9)).run[F]
      _ <- assertResultF(m.get(42).run[F], Some(21))
      _ <- assertResultF(m.get(0).run[F], Some(9))
      _ <- assertResultF(m.del(56).run[F], false)
      _ <- assertResultF(m.get(42).run[F], Some(21))
      _ <- assertResultF(m.get(0).run[F], Some(9))
      _ <- assertResultF((m.del(42)).run[F], true)
      _ <- assertResultF(m.get(42).run[F], None)
      _ <- assertResultF(m.get(0).run[F], Some(9))
      _ <- assertResultF((m.del(42)).run[F], false)
      _ <- assertResultF(m.get(42).run[F], None)
      _ <- assertResultF(m.get(0).run[F], Some(9))
    } yield ()
  }

  test("Map should perform replace correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (m.put(42, "foo")).run[F]
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.replace(42, "xyz", "bar").run[F], false)
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.replace(42, "foo", "bar").run[F], true)
      _ <- assertResultF(m.get(42).run[F], Some("bar"))
      _ <- assertResultF(m.replace(99, "foo", "bar").run[F], false)
      _ <- assertResultF(m.get(42).run[F], Some("bar"))
    } yield ()
  }

  test("Map should perform remove correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (m.put(42, "foo")).run[F]
      _ <- (m.put(99, "bar")).run[F]
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.remove(42, "x").run[F], false)
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.remove(42, "foo").run[F], true)
      _ <- assertResultF(m.get(42).run[F], None)
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.remove(42, "foo").run[F], false)
      _ <- assertResultF(m.get(42).run[F], None)
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
    } yield ()
  }

  test("Map should perform putIfAbsent correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (m.put(42, "foo")).run[F]
      _ <- (m.put(99, "bar")).run[F]
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.putIfAbsent(42, "xyz").run[F], Some("foo"))
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.putIfAbsent(84, "xyz").run[F], None)
      _ <- assertResultF(m.get(42).run[F], Some("foo"))
      _ <- assertResultF(m.get(99).run[F], Some("bar"))
      _ <- assertResultF(m.get(84).run[F], Some("xyz"))
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
      _ <- (m.put(0, "0")).run[F]
      _ <- (m.put(1, "1")).run[F]
      _ <- (m.put(4, "4")).run[F]
      _ <- assertResultF(m.get(0).run[F], Some("0"))
      _ <- assertResultF(m.get(1).run[F], Some("1"))
      _ <- assertResultF(m.get(4).run[F], Some("4"))
      _ <- assertResultF(m.get(8).run[F], Some("0"))
      _ <- (m.put(8, "8")).run[F] // overwrites "0"
      _ <- assertResultF(m.get(0).run[F], Some("8"))
      _ <- assertResultF(m.get(1).run[F], Some("1"))
      _ <- assertResultF(m.get(4).run[F], Some("4"))
      _ <- assertResultF(m.get(8).run[F], Some("8"))
      _ <- assertResultF(m.del(16).run[F], true) // deletes "8"
      _ <- assertResultF(m.get(0).run[F], None)
      _ <- assertResultF(m.get(1).run[F], Some("1"))
      _ <- assertResultF(m.get(4).run[F], Some("4"))
      _ <- assertResultF(m.get(8).run[F], None)
    } yield ()
  }

  test("Map get should not find anything in an empty map") {
    PropF.forAllF { (i: Int) =>
      mkEmptyMap[Int, Int].flatMap { m =>
        assertResultF(m.get(i).run[F], None)
      }
    }
  }

  test("Map get should find a previously inserted single key") {
    PropF.forAllF { (k: Int, i: Int) =>
      for {
        m <- mkEmptyMap[Int, String]
        _ <- m.put(k, k.toString).run[F]
        _ <- assertResultF(m.get(k).run[F], Some(k.toString))
        _ <- if (i =!= k) {
          assertResultF(m.get(i).run[F], None)
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
            _ <- m.put(k, k.toString).run[F]
            _ <- F.delay { shadow += k }
            _ <- shadow.toList.traverse { i =>
              assertResultF(m.get(i).run[F], Some(i.toString))
            }
            cont <- F.delay { shadow.contains(x) }
            _ <- if (!cont) {
              assertResultF(m.get(x).run[F], None)
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
      _ <- m.put(0, "0").run[F]
      _ <- assertResultF(m.get(0).run, Some("0"))
      _ <- assertResultF(m.get(8).run, Some("0"))
      _ <- m.put(4, "4").run
      _ <- assertResultF(m.get(0).run, Some("0"))
      _ <- assertResultF(m.get(8).run, Some("0"))
      _ <- assertResultF(m.get(4).run, Some("4"))
      _ <- assertResultF(m.get(12).run, Some("4"))
    } yield ()
  }

  test("Map del should remove the kv pair") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- m.put(0, "0").run[F]
      _ <- assertResultF(m.get(0).run, Some("0"))
      _ <- assertResultF(m.del(0).run[F], true)
      _ <- assertResultF(m.del(0).run[F], false)
      _ <- m.put(1, "1").run
      _ <- m.put(2, "2").run
      _ <- m.put(3, "3").run
      _ <- m.put(4, "4").run
      _ <- m.put(5, "5").run
      _ <- m.put(6, "6").run
      _ <- m.put(7, "7").run
      _ <- m.put(8, "8").run
      _ <- assertResultF(m.get(1).run, Some("1"))
      _ <- assertResultF(m.del(1).run[F], true)
      _ <- assertResultF(m.del(1).run[F], false)
      _ <- assertResultF(m.get(8).run, Some("8"))
      _ <- assertResultF(m.del(8).run[F], true)
      _ <- assertResultF(m.del(8).run[F], false)
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
        _ <- m.put(x, x.toString).run[F]
        _ <- assertResultF(m.get(x).run, Some(x.toString))
        _ <- m.put(x + 8, (x + 8).toString).run
        _ <- assertResultF(m.get(x).run, Some(x.toString))
        _ <- assertResultF(m.get(x + 8).run, Some((x + 8).toString))
        _ <- m.put(x + 16, (x + 16).toString).run
        _ <- assertResultF(m.get(x).run, Some(x.toString))
        _ <- assertResultF(m.get(x + 8).run, Some((x + 8).toString))
        _ <- assertResultF(m.get(x + 16).run, Some((x + 16).toString))
        _ <- m.put(x + 1, (x + 1).toString).run
        _ <- assertResultF(m.get(x).run, Some(x.toString))
        _ <- assertResultF(m.get(x + 8).run, Some((x + 8).toString))
        _ <- assertResultF(m.get(x + 16).run, Some((x + 16).toString))
        _ <- assertResultF(m.get(x + 1).run, Some((x + 1).toString))
        _ <- m.put(x + 9, (x + 9).toString).run
        _ <- assertResultF(m.get(x).run, Some(x.toString))
        _ <- assertResultF(m.get(x + 8).run, Some((x + 8).toString))
        _ <- assertResultF(m.get(x + 16).run, Some((x + 16).toString))
        _ <- assertResultF(m.get(x + 1).run, Some((x + 1).toString))
        _ <- assertResultF(m.get(x + 9).run, Some((x + 9).toString))
        _ <- ks.toList.traverse { k =>
          m.put(k, k.toString).run.flatMap { _ =>
            assertResultF(m.get(k).run, Some(k.toString))
          }
        }
        _ <- m.put(x + 17, (x + 17).toString).run
        _ <- assertResultF(m.get(x + 17).run, Some((x + 17).toString))
      } yield ()
    }
  }

  test("Resurrecting a tombed but uncommitted ref") {
    for {
      m <- mkEmptyMap[Int, String]
      ctr <- Ref(0).run[F]
      _ <- m.put(0, "x").run[F]
      _ <- m.put(0, "0").run
      _ <- m.put(1, "1").run
      _ <- m.put(2, "2").run
      _ <- m.put(3, "3").run
      _ <- m.put(4, "4").run
      ref = m.del(0) *> ctr.update(_ + 1) *> m.put(0, "y")
      _ <- ref.run[F]
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(m.get(0).run[F], Some("y"))
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

  test("Map#asCats (MapRef)") {
    for {
      m <- mkEmptyMap[String, Option[Int]]
      mapRef = m.asCats[F](default = None)
      r1 = mapRef("a")
      r2 = mapRef("b")
      r3 = mapRef("c")
      _ <- r1.set(Some(42))
      _ <- assertResultF(r1.get, Some(42))
      _ <- r2.update(_ => Some(23))
      _ <- assertResultF(m.get("a").run[F], Some(Some(42)))
      _ <- assertResultF(m.get("b").run[F], Some(Some(23)))
      _ <- assertResultF(m.get("c").run[F], None)
      _ <- r3.set(Some(99))
      _ <- assertResultF(m.get("c").run[F], Some(Some(99)))
      _ <- r3.set(None)
      _ <- assertResultF(m.get("c").run[F], None) // NB: it's `None` and not `Some(None)`
    } yield ()
  }

  test("Map invariant functor instance") {
    for {
      m <- mkEmptyMap[String, String]
      _ <- m.put("foo", "42").run
      m2 = (m: Map[String, String]).imap(_.toInt)(_.toString)
      _ <- assertResultF(m2.get("foo").run, Some(42))
      _ <- assertResultF(m2.get("bar").run, None)
      r = m2.refLike("foo", 0)
      _ <- r.update(_ + 1).run
      _ <- assertResultF(m.get("foo").run, Some("43"))
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
        _ <- assertResultF(m.get(null).run, None)
        _ <- ks.traverse_ { k =>
          m.put(k, v).run[F]
        }
        _ <- assertResultF(m.get(null).run, None)
        _ <- m.put(k0, null).run[F]
        _ <- assertResultF(m.get(null).run, None)
        _ <- assertResultF(m.get(k0).run, Some(null))
        _ <- ks.traverse_ { k =>
          assertResultF(m.get(k).run[F], Some(v))
        }
        _ <- assertResultF(m.putIfAbsent(k0, v).run, Some(null))
        _ <- assertResultF(m.putIfAbsent(null, "x").run, None)
        _ <- assertResultF(m.get(null).run, Some("x"))
        _ <- assertResultF(m.get(k0).run, Some(null))
        _ <- ks.traverse_ { k =>
          assertResultF(m.get(k).run[F], Some(v))
        }
        _ <- assertResultF(m.replace(null, "x", null).run, true)
        _ <- assertResultF(m.get(null).run, Some(null))
        _ <- assertResultF(m.get(k0).run, Some(null))
        _ <- ks.traverse_ { k =>
          assertResultF(m.get(k).run[F], Some(v))
        }
      } yield ()
    }
  }
}
