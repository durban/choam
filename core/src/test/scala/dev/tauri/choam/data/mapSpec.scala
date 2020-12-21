/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.collection.immutable

import cats.effect.SyncIO

class MapSpec_NaiveKCAS_SyncIO
  extends SyncIOSpecMUnit
  with SpecNaiveKCAS
  with MapSpec[SyncIO]

class MapSpec_EMCAS_SyncIO
  extends SyncIOSpecMUnit
  with SpecEMCAS
  with MapSpec[SyncIO]

trait MapSpec[F[_]] extends BaseSpecSyncF[F] { this: KCASImplSpec =>

  def mkEmptyMap[K, V]: F[Map[K, V]] =
    Map.naive[K, V].run[F]

  test("Map should perform put correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (React.pure(42 -> 21) >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21))
      _ <- (React.pure(44 -> 22) >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21, 44 -> 22))
      _ <- (React.pure(42 -> 0) >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 0, 44 -> 22))
    } yield ()
  }

  test("Map should perform get correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (React.pure(42 -> 21) >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21))
      _ <- assertResultF(m.get.apply[F](42), Some(21))
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21))
      _ <- assertResultF(m.get.apply[F](99), None)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21))
    } yield ()
  }

  test("Map should perform del correctly") {
    for {
      m <- mkEmptyMap[Int, Int]
      _ <- (React.pure(42 -> 21) >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21))
      _ <- assertResultF((React.pure(56) >>> m.del).run[F], false)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> 21))
      _ <- assertResultF((React.pure(42) >>> m.del).run[F], true)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map.empty[Int, Int])
      _ <- assertResultF((React.pure(42) >>> m.del).run[F], false)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map.empty[Int, Int])
    } yield ()
  }

  test("Map should perform replace correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (React.pure(42 -> "foo") >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "foo"))
      _ <- assertResultF(m.replace.apply[F]((42, "xyz", "bar")), false)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "foo"))
      _ <- assertResultF(m.replace.apply[F]((42, "foo", "bar")), true)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "bar"))
      _ <- assertResultF(m.replace.apply[F]((99, "foo", "bar")), false)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "bar"))
    } yield ()
  }

  test("Map should perform remove correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (React.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (React.pure(99 -> "bar") >>> m.put).run[F]
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "foo", 99 -> "bar"))
      _ <- assertResultF(m.remove.apply[F](42 -> "x"), false)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "foo", 99 -> "bar"))
      _ <- assertResultF(m.remove.apply[F](42 -> "foo"), true)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(99 -> "bar"))
      _ <- assertResultF(m.remove.apply[F](42 -> "foo"), false)
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(99 -> "bar"))
    } yield ()
  }

  test("Map should perform clear correctly") {
    for {
      m <- mkEmptyMap[Int, String]
      _ <- (React.pure(42 -> "foo") >>> m.put).run[F]
      _ <- (React.pure(99 -> "bar") >>> m.put).run
      _ <- assertResultF(m.snapshot.run[F], immutable.Map(42 -> "foo", 99 -> "bar"))
      _ <- assertResultF(m.clear.run[F], immutable.Map(42 -> "foo", 99 -> "bar"))
      _ <- assertResultF(m.snapshot.run[F], immutable.Map.empty[Int, String])
      _ <- assertResultF(m.clear.run[F], immutable.Map.empty[Int, String])
      _ <- assertResultF(m.snapshot.run[F], immutable.Map.empty[Int, String])
    } yield ()
  }
}
