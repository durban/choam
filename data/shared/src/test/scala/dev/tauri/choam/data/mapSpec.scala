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
package data

import cats.kernel.Hash
import cats.effect.SyncIO

final class MapSpec_ThreadConfinedMCAS_SyncIO
  extends BaseSpecSyncIO
  with SpecThreadConfinedMCAS
  with MapSpec[SyncIO]

trait MapSpec[F[_]] extends BaseSpecSyncF[F] { this: KCASImplSpec =>

  def mkEmptyMap[K: Hash, V]: F[Map[K, V]] =
    Map.simple[K, V].run[F]

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

  test("Map should perform clear correctly") {
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
}
