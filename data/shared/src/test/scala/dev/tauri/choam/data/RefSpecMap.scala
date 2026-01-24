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

import scala.collection.immutable.{ Map => ScalaMap, Set => ScalaSet }

import cats.kernel.{ Hash, Order }
import cats.effect.IO

import core.{ RefLike, RefLikeSpec }

final class RefSpec_Map_SimpleHash_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Map_SimpleHash[IO]

final class RefSpec_Map_SimpleOrdered_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Map_SimpleOrdered[IO]

trait RefSpec_Map_SimpleHash[F[_]] extends RefSpecMap[F] { this: McasImplSpec =>

  final override type MapType[K, V] = Map.Extra[K, V]

  final override def newMap[K : Hash : Order, V]: F[MapType[K, V]] =
    Map.simpleHashMap[K, V].run[F]
}

trait RefSpec_Map_SimpleOrdered[F[_]] extends RefSpecMap[F] { this: McasImplSpec =>

  final override type MapType[K, V] = Map.Extra[K, V]

  final override def newMap[K : Hash : Order, V]: F[MapType[K, V]] =
    Map.simpleOrderedMap[K, V].run[F]
}

trait RefSpecMap[F[_]] extends RefLikeSpec[F] { this: McasImplSpec =>

  private[data] type MapType[K, V] <: Map[K, V]

  final override type RefType[A] = RefLike[A]

  final override def newRef[A](initial: A): F[RefType[A]] =
    newMap[String, A].map(_.refLike("foo", default = initial))

  def newMap[K : Hash : Order, V]: F[MapType[K, V]]

  def newRandomMap[K : Hash : Order, V](genK: F[K], genV: F[V], size: Int): F[MapType[K, V]] = {
    this.newMap[K, V].flatMap { m =>
      def go(currSize: Int): F[Unit] = {
        if (currSize < size) {
          (genK, genV).mapN { (k, v) =>
            m.put(k, v).run[F]
          }.flatten.flatMap {
            case Some(_) => go(currSize)
            case None => go(currSize + 1)
          }
        } else {
          F.unit
        }
      }
      go(currSize = 0).as(m)
    }
  }

  def newRandomStringMap[V](genV: F[V], size: Int): F[MapType[String, V]] = {
    F.delay { new scala.util.Random() }.flatMap { rng =>
      newRandomMap[String, V](
        genK = F.delay { rng.nextString(length = 32) },
        genV = genV,
        size = size
      )
    }
  }

  test("Map put, update, del (sequential)") {
    val N = 1024
    for {
      m <- newMap[String, Int]
      _ <- (1 to N).toList.traverse { n =>
        m.put(n.toString, n).run[F]
      }.void
      refs = (1 to N).toList.reverse.map { n =>
        m.refLike(key = n.toString, default = 0)
      }
      _ <- refs.traverse { ref =>
        ref.update(_ * 3).run[F]
      }.void
      _ <- (1 to N).toList.traverse { n =>
        assertResultF(m.get(n.toString).run[F], Some(n * 3)) *> (
          assertResultF(m.get((-n).toString).run[F], None)
        )
      }.void
      _ <- (1 to N).toList.traverse { n =>
        if ((n % 2) == 0) {
          assertResultF(m.del((n).toString).run[F], true)
        } else {
          assertResultF(m.get(n.toString).run[F], Some(n * 3))
        }
      }.void
    } yield ()
  }

  test("Map put, update, del (parallel)") {
    val N = 1024
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      m <- newMap[String, Int]
      _ <- (1 to N).toList.parTraverseN(512) { n =>
        m.put(n.toString, n).run[F]
      }.void
      refs = (1 to N).toList.reverse.map { n =>
        m.refLike(key = n.toString, default = 0)
      }
      _ <- refs.parTraverseN(512) { ref =>
        ref.update(_ * 3).run[F]
      }.void
      _ <- (1 to N).toList.parTraverseN(512) { n =>
        assertResultF(m.get(n.toString).run[F], Some(n * 3)) *> (
          assertResultF(m.get((-n).toString).run[F], None)
        )
      }.void
      _ <- (1 to N).toList.parTraverseN(512) { n =>
        if ((n % 2) == 0) {
          assertResultF(m.del((n).toString).run[F], true)
        } else {
          assertResultF(m.get(n.toString).run[F], Some(n * 3))
        }
      }.void
    } yield ()
  }

  test("Map unsafeSnapshot") {
    for {
      m <- newMap[String, Int]
      _ <- assertResultF(Map.unsafeSnapshot(m), ScalaMap.empty[String, Int])
      _ <- m.put("a", 1).run[F]
      _ <- m.put("b", 2).run[F]
      _ <- m.put("c", 3).run[F]
      _ <- m.put("a", 42).run[F]
      _ <- assertResultF(
        Map.unsafeSnapshot(m),
        ScalaMap("a" -> 42, "b" -> 2, "c" -> 3)
      )
      r <- newRandomStringMap[Int](genV = F.pure(42), size = 1024)
      s <- Map.unsafeSnapshot(r)
      _ <- assertF(s.size >= (1024 * 0.9))
      _ <- assertEqualsF(s.values.toSet, ScalaSet(42))
    } yield ()
  }

  test("Setting ref to default value should remove from the map") {
    for {
      m <- newMap[String, String]
      ref = m.refLike(key = "foo", default = "")
      _ <- ref.set("bar").run[F]
      _ <- assertResultF(ref.get.run[F], "bar")
      _ <- ref.set("").run[F]
      _ <- assertResultF(Map.unsafeSnapshot(m), ScalaMap.empty[String, String])
      _ <- ref.set("abc").run[F]
      _ <- assertResultF(Map.unsafeSnapshot(m), ScalaMap("foo" -> "abc"))
      _ <- ref.set("").run[F]
      _ <- assertResultF(Map.unsafeSnapshot(m), ScalaMap.empty[String, String])
    } yield ()
  }

  test("Map double get with concurrent insert") {
    val S = 1024
    val N = 1024
    val P = 512
    val rng = new scala.util.Random()
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      m <- newRandomMap[String, String](
        genK = F.delay { rng.nextString(length = 24) },
        genV = F.pure("value"),
        size = S,
      )
      _ <- assertResultF(Map.unsafeGetSize(m), S)
      doubleGet = (key: String) => (m.get(key) * m.get(key)).run[F]
      insert = (key: String) => m.put(key, "x").run[F]
      both = (key: String) => F.both(
        F.cede *> doubleGet(key),
        F.cede *> insert(key),
      ).map(_._1)
      keys <- F.delay {
        // we generate different length strings,
        // so these are not in the map for sure:
        List.fill(N) { rng.nextString(length = 25) }
      }
      results <- keys.parTraverseN(P)(both)
      // get results must be consistent:
      _ <- assertF(clue(results).forall(r => r._1 == r._2))
      // if found it, the value must be "x":
      _ <- assertF(clue(results).forall(r => r._1.getOrElse("x") == "x"))
      // map must have changed size:
      _ <- assertResultF(Map.unsafeGetSize(m), S + N)
    } yield ()
  }

  test("Map double get with concurrent delete") {
    val S = 2048
    val P = 512
    val rng = new scala.util.Random()
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      m <- newRandomMap[String, String](
        genK = F.delay { rng.nextString(length = 24) },
        genV = F.pure("x"),
        size = S,
      )
      _ <- assertResultF(Map.unsafeGetSize(m), S)
      doubleGet = (key: String) => (m.get(key) * m.get(key)).run[F]
      delete = (key: String) => m.del(key).run[F]
      both = (key: String) => F.both(
        F.cede *> doubleGet(key),
        F.cede *> delete(key),
      )
      snap <- Map.unsafeSnapshot(m)
      keys <- F.delay {
        // we select half of the keys:
        val ks = rng.shuffle(snap.keys.toList)
        assertEquals(ks.length, S)
        assertEquals(ks.toSet.size, S)
        ks.take(S / 2)
      }
      _ <- keys.traverse { key =>
        m.get(key).run[F].flatMap { res =>
          assertF(res.isDefined)
        }
      }
      allResults <- keys.parTraverseN(P)(both)
      results = allResults.map(_._1)
      delResults = allResults.map(_._2)
      // get results must be consistent:
      _ <- assertF(clue(results).forall(r => r._1 == r._2))
      // if found it, the value must be "x":
      _ <- assertF(clue(results).forall(r => r._1.getOrElse("x") == "x"))
      // del results must be successful:
      _ <- assertF(clue(delResults).forall(r => r))
      // map must've halved in size:
      _ <- assertResultF(Map.unsafeGetSize(m), S / 2)
    } yield ()
  }
}
