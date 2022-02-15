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
import cats.effect.IO

final class RefSpec_Map_Simple_ThreadConfinedMCAS_IO
  extends BaseSpecIO
  with SpecThreadConfinedMCAS
  with RefSpec_Map_Simple[IO]

trait RefSpec_Map_Simple[F[_]] extends RefSpecMap[F] { this: KCASImplSpec =>

  final override type MapType[K, V] = Map.Extra[K, V]

  final override def newMap[K: Hash, V]: F[MapType[K, V]] =
    Map.simple[K, V].run[F]
}

trait RefSpecMap[F[_]] extends RefLikeSpec[F] { this: KCASImplSpec =>

  type MapType[K, V] <: Map[K, V]

  final override type RefType[A] = RefLike[A]

  final override def newRef[A](initial: A): F[RefType[A]] =
    newMap[String, A].map(_.refLike("foo", default = initial))

  def newMap[K: Hash, V]: F[MapType[K, V]]

  test("Map put, update, del (sequential)") {
    val N = 1024
    for {
      m <- newMap[String, Int]
      _ <- (1 to N).toList.traverse { n =>
        m.put[F](n.toString -> n)
      }.void
      refs = (1 to N).toList.reverse.map { n =>
        m.refLike(key = n.toString, default = 0)
      }
      _ <- refs.traverse { ref =>
        ref.update(_ * 3).run[F]
      }.void
      _ <- (1 to N).toList.traverse { n =>
        assertResultF(m.get[F](n.toString), Some(n * 3)) *> (
          assertResultF(m.get[F]((-n).toString), None)
        )
      }.void
      _ <- (1 to N).toList.traverse { n =>
        if ((n % 2) == 0) {
          assertResultF(m.del[F]((n).toString), true)
        } else {
          assertResultF(m.get[F](n.toString), Some(n * 3))
        }
      }.void
    } yield ()
  }

  test("Map put, update, del (parallel)") {
    val N = 1024
    for {
      _ <- assumeF(this.kcasImpl.isThreadSafe)
      m <- newMap[String, Int]
      _ <- (1 to N).toList.parTraverseN(512) { n =>
        m.put[F](n.toString -> n)
      }.void
      refs = (1 to N).toList.reverse.map { n =>
        m.refLike(key = n.toString, default = 0)
      }
      _ <- refs.parTraverseN(512) { ref =>
        ref.update(_ * 3).run[F]
      }.void
      _ <- (1 to N).toList.parTraverseN(512) { n =>
        assertResultF(m.get[F](n.toString), Some(n * 3)) *> (
          assertResultF(m.get[F]((-n).toString), None)
        )
      }.void
      _ <- (1 to N).toList.parTraverseN(512) { n =>
        if ((n % 2) == 0) {
          assertResultF(m.del[F]((n).toString), true)
        } else {
          assertResultF(m.get[F](n.toString), Some(n * 3))
        }
      }.void
    } yield ()
  }
}
